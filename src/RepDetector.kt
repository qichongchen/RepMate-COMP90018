package engine

import kotlin.math.sqrt

/**
 * Counts squat repetitions from the **magnitude** of acceleration, smoothed by a
 * moving-average filter.
 *
 * ## Why magnitude instead of a single axis
 * The first version of this detector watched [MotionFrame.ay] alone, which only works if
 * the phone is upright and stays that way. In reality the phone sits in a pocket at
 * whatever angle the user shoved it in, and it shifts during the set. Gravity then leaks
 * across all three axes in proportions we cannot predict, so "vertical" is not a fixed
 * axis and no single channel is reliable.
 *
 * The magnitude `sqrt(ax^2 + ay^2 + az^2)` is **rotation-invariant**: it is the same
 * number no matter how the phone is oriented. Because Sensor Logger exports *total*
 * acceleration (gravity included), a still phone reads ~9.81 m/s^2 at any angle, and
 * every real movement — the drop into the squat, the drive out of the bottom — pushes
 * the magnitude away from that resting value. So we get one orientation-proof channel
 * instead of three fragile ones.
 *
 * The trade-off, stated plainly: magnitude throws away **direction**. `|a|` cannot tell
 * "accelerating down" from "accelerating up", so this detector cannot separate the
 * descent from the ascent. See the state-machine note below.
 *
 * ## Smoothing
 * Raw magnitude on this hardware swings between ~4.8 and ~27.8 m/s^2 — mostly footfall
 * shock and pocket rustle, not squatting. A [smoothingWindow]-sample moving average
 * (25 samples is about 250 ms at the ~100 Hz this trace was recorded at) flattens that
 * to a ~8.2 to ~13.6 band in which the squat cycle is the dominant feature. The filter
 * is a plain unweighted running mean: cheap, causal (it never looks at future samples,
 * so it behaves identically live and in replay), and easy to explain.
 *
 * During the first [smoothingWindow] frames the buffer is not yet full, so the average is
 * taken over however many samples have arrived. That warm-up is under a third of a second
 * and precedes any real movement.
 *
 * ## The state machine
 * Two thresholds form a **Schmitt trigger** — a deliberate gap between the "on" and "off"
 * levels, so a signal hovering at one value cannot rattle the state back and forth:
 *
 * ```
 *   IDLE ----------- smoothed > highThreshold -----------> DESCENDING  (rep window opens)
 *   DESCENDING ----- smoothed < lowThreshold ------------> IDLE        (window closes,
 *                                                                       emit if guards pass)
 * ```
 *
 * Only two of the five [RepPhase] values are reachable here. Because magnitude is
 * direction-blind (above), the detector genuinely cannot know whether the user is on the
 * way down, paused at the bottom, or driving up — so reporting [RepPhase.BOTTOM] or
 * [RepPhase.ASCENDING] would be a lie. [RepPhase.DESCENDING] is used to mean "a movement
 * burst is in progress", named for the descent that opens it. Recovering the real phases
 * needs the gyroscope or a gravity-vector estimate; that is a later step.
 *
 * ## The guards (why each threshold exists)
 * Every parameter below rejects a specific false positive seen in real data:
 *
 * - [highThreshold] — how far above resting gravity the smoothed signal must climb to open
 *   a rep. Set just above 9.81 so a genuine squat clears it but a phone resting in a still
 *   pocket never does.
 * - [lowThreshold] — how far it must fall to close the rep. Set *below* [highThreshold],
 *   not equal to it: that hysteresis gap is what stops one wobbly rep counting as three.
 * - [minRepDurationMs] — a rep must stay open at least this long. A squat takes roughly
 *   0.6 to 1.1 s in this trace; a footstep, a bump, or the phone settling in the pocket
 *   spikes and clears in well under half a second.
 * - [cooldownMs] — a new rep may not start until this long after the previous one ended.
 *   Humans do not squat twice in two seconds; this suppresses the rebound wobble that
 *   follows a rep already counted.
 *
 * ## Timing and amplitude
 * `startMs` is the timestamp of the frame that crossed [highThreshold]; `endMs` is the frame
 * that fell back under [lowThreshold]. `amplitude` is the peak-to-peak swing of the
 * **smoothed** magnitude across that window — a rough proxy for how forceful the rep was.
 * It is measured on the smoothed signal on purpose, so one noise spike cannot inflate it.
 *
 * Pure Kotlin, no Android dependencies. Instances are stateful and not thread-safe: use one
 * detector per workout session and feed it frames in chronological order.
 *
 * @param highThreshold m/s^2 the smoothed magnitude must exceed to open a rep. Default 10.15,
 *   tuned on a real 10-squat pocket recording.
 * @param lowThreshold m/s^2 the smoothed magnitude must fall under to close a rep. Default
 *   9.7, just below resting gravity.
 * @param minRepDurationMs minimum open-to-close time for a rep to count. Default 500 ms.
 * @param cooldownMs minimum quiet time between the end of one counted rep and the start of
 *   the next. Default 2000 ms.
 * @param smoothingWindow samples in the moving-average filter. Default 25 (~250 ms at 100 Hz).
 */
class SquatRepDetector(
    private val highThreshold: Float = 10.15f,
    private val lowThreshold: Float = 9.7f,
    private val minRepDurationMs: Long = 500L,
    private val cooldownMs: Long = 2000L,
    private val smoothingWindow: Int = 25
) {

    init {
        require(smoothingWindow >= 1) { "smoothingWindow must be at least 1" }
        require(lowThreshold < highThreshold) {
            "lowThreshold ($lowThreshold) must sit below highThreshold ($highThreshold) " +
                "so the two form a hysteresis gap"
        }
    }

    private val _reps = mutableListOf<RepEvent>()

    /** Reps detected so far, in the order they completed. */
    val reps: List<RepEvent> get() = _reps

    /** How many full reps have been counted so far. */
    val repCount: Int get() = _reps.size

    /** The phase the detector is currently in. Starts at [RepPhase.IDLE]. */
    var phase: RepPhase = RepPhase.IDLE
        private set

    /** The most recent smoothed magnitude, exposed for debugging and threshold tuning. */
    var smoothedMagnitude: Float = 0f
        private set

    // --- Moving-average filter state ------------------------------------------------
    // A fixed-size circular buffer plus a running sum, so each frame costs O(1) instead
    // of re-summing the whole window.
    private val window = FloatArray(smoothingWindow)
    private var windowNext = 0 // index the next sample overwrites
    private var windowFilled = 0 // how many slots hold real data (caps at smoothingWindow)
    private var windowSum = 0.0

    // --- Rep-in-progress state --------------------------------------------------------
    private var repStartMs: Long = 0L
    private var minSmoothed: Float = Float.MAX_VALUE
    private var maxSmoothed: Float = -Float.MAX_VALUE

    /** Timestamp at which the last *counted* rep ended; null until one has been counted. */
    private var lastRepEndMs: Long? = null

    /**
     * Feeds one frame into the filter and the state machine.
     *
     * @return the [RepEvent] if this frame completed a rep that passed every guard, or
     *   `null` otherwise — including when a rep window closed but was rejected by a guard.
     */
    fun process(frame: MotionFrame): RepEvent? {
        val smoothed = smooth(magnitudeOf(frame))
        smoothedMagnitude = smoothed

        when (phase) {
            RepPhase.IDLE -> {
                if (smoothed > highThreshold) {
                    // Movement burst begins: open a rep window and start tracking its swing.
                    phase = RepPhase.DESCENDING
                    repStartMs = frame.tMillis
                    minSmoothed = smoothed
                    maxSmoothed = smoothed
                }
            }

            RepPhase.DESCENDING -> {
                trackExtremes(smoothed)
                if (smoothed < lowThreshold) {
                    // Movement burst is over: close the window and apply the guards.
                    val event = closeRepWindow(frame.tMillis)
                    phase = RepPhase.IDLE
                    resetRepWindow()
                    return event
                }
            }

            // Unreachable: magnitude is direction-blind, so these phases are never entered.
            // Listed so the `when` stays exhaustive and adding transitions later is a
            // compile-time prompt rather than a silent fallthrough.
            RepPhase.BOTTOM, RepPhase.ASCENDING, RepPhase.TOP -> Unit
        }

        return null
    }

    /**
     * Convenience helper: runs a whole trace through [process] and returns every rep
     * detected *by this call*. State carries over, so a detector can be fed in chunks.
     */
    fun processAll(frames: List<MotionFrame>): List<RepEvent> =
        frames.mapNotNull { process(it) }

    /** Clears detected reps, empties the filter, and returns the machine to [RepPhase.IDLE]. */
    fun reset() {
        _reps.clear()
        resetRepWindow()
        phase = RepPhase.IDLE
        lastRepEndMs = null
        smoothedMagnitude = 0f
        window.fill(0f)
        windowNext = 0
        windowFilled = 0
        windowSum = 0.0
    }

    /** Orientation-invariant size of the acceleration vector, gravity included. */
    private fun magnitudeOf(frame: MotionFrame): Float =
        sqrt(frame.ax * frame.ax + frame.ay * frame.ay + frame.az * frame.az)

    /**
     * Pushes one sample through the moving average and returns the new mean.
     *
     * Until the buffer fills, the mean is taken over the samples received so far. The
     * running sum is a `Double` so thousands of float additions cannot drift.
     */
    private fun smooth(sample: Float): Float {
        if (windowFilled == smoothingWindow) windowSum -= window[windowNext] else windowFilled++
        window[windowNext] = sample
        windowSum += sample
        windowNext = (windowNext + 1) % smoothingWindow
        return (windowSum / windowFilled).toFloat()
    }

    /**
     * Applies the duration and cooldown guards to the rep window that just closed.
     *
     * @return the recorded [RepEvent], or `null` if a guard rejected the window.
     */
    private fun closeRepWindow(endMs: Long): RepEvent? {
        if (endMs - repStartMs < minRepDurationMs) return null // too brief to be a squat

        val previousEnd = lastRepEndMs
        if (previousEnd != null && repStartMs - previousEnd < cooldownMs) {
            return null // too soon after the last rep — almost certainly its rebound
        }

        val event = RepEvent(
            index = _reps.size,
            startMs = repStartMs,
            endMs = endMs,
            amplitude = maxSmoothed - minSmoothed
        )
        _reps += event
        lastRepEndMs = endMs
        return event
    }

    private fun trackExtremes(smoothed: Float) {
        if (smoothed < minSmoothed) minSmoothed = smoothed
        if (smoothed > maxSmoothed) maxSmoothed = smoothed
    }

    private fun resetRepWindow() {
        repStartMs = 0L
        minSmoothed = Float.MAX_VALUE
        maxSmoothed = -Float.MAX_VALUE
    }
}
