package com.repmate.engine

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
 * shock and pocket rustle, not squatting. A [smoothingWindowMs]-long moving average
 * flattens that to a band in which the squat cycle is the dominant feature. The filter is
 * a plain unweighted running mean: cheap, causal (it never looks at future samples, so it
 * behaves identically live and in replay), and easy to explain.
 *
 * ### Why the window is a duration and not a sample count
 * This filter was originally specified as **25 samples**, which is 250 ms only if the
 * stream happens to run at 100 Hz. Sample rate is not ours to choose: it is whatever the
 * device, the OS and the current sensor batching hand us. Those same 25 samples measured
 * **250 ms** on the two ~99 Hz Pixel recordings, **430 ms** on the ~58 Hz Samsung
 * recording, and **500 ms** on a 50 Hz stream.
 *
 * A moving average's cutoff frequency is set by its length *in time*, so a sample count
 * silently re-tuned the filter on every device — and with it every threshold below, because
 * those thresholds are all measured on the filter's *output*. A shorter filter passes more
 * of each rep's peak, widening the amplitude swings and sharpening the window edges; a
 * longer one flattens them. [minAmplitude] in particular was being asked to separate a real
 * rep from a wobble using numbers that moved whenever the rate did. Three separate
 * threshold hunts were really this one bug wearing different hats.
 *
 * So the window is declared in milliseconds and the sample count is **derived from the
 * observed frame timing at runtime**: every frame evicts whatever has aged out of the
 * trailing [smoothingWindowMs], so the average is always taken over exactly the samples
 * that arrived in that span. At ~99 Hz that is ~25 samples, at ~58 Hz ~15, at 50 Hz ~13 —
 * different counts, the same 250 ms of signal, the same cutoff frequency.
 *
 * Eviction is done on the timestamps themselves rather than by estimating a rate and
 * computing a length from it. It is simpler, it needs no warm-up before the rate is known,
 * and a rate that drifts or stalls *mid-set* is absorbed frame by frame instead of at the
 * next release. [smoothingSampleCount] and [observedSampleRateHz] report what it settled
 * on, so a stream running at an unexpected rate shows up in a log rather than surfacing
 * later as an unexplained miscount.
 *
 * During the first [smoothingWindowMs] the window is not yet full, so the average is taken
 * over however many samples have arrived. That warm-up is a quarter of a second — and now
 * a quarter of a second on every device — and precedes any real movement.
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
 * Every parameter below rejects a specific false positive seen in real data. Every figure
 * quoted is measured on the **smoothed** signal, so all of them were re-measured against the
 * 250 ms filter above; the previous set was taken with a filter that was 250 ms on the two
 * Pixel traces but 430 ms on the Samsung one, and the difference is not cosmetic — see the
 * amplitude bullet.
 *
 * - [highThreshold] — how far above resting gravity the smoothed signal must climb to open
 *   a rep. Set just above 9.81 so a genuine squat clears it but a phone resting in a still
 *   pocket never does.
 * - [lowThreshold] — how far it must fall to close the rep. Set *below* [highThreshold],
 *   not equal to it: that hysteresis gap is what stops one wobbly rep counting as three.
 * - [minRepDurationMs] — a rep window must stay open at least this long. The shortest real
 *   rep across the three recordings runs **615 ms** (Mohit, normal pace), so 550 ms clears
 *   every genuine rep with 65 ms to spare, and 620 ms would already discard one. A rep a
 *   few per cent quicker than the fastest recorded one being dropped silently is the
 *   failure a user notices most, so the headroom is deliberate. Note that this guard no
 *   longer rejects the wobble it was originally sized against — see the redundancy note
 *   below.
 * - [maxRepDurationMs] — a window open longer than this is **abandoned**, not counted. The
 *   longest genuine rep in the corpus runs **1573 ms** (Hit's tenth squat); 3000 ms leaves
 *   1427 ms of headroom, near twice the longest rep, and still clears the 2195 ms
 *   phone-into-pocket burst that Hit's `fullTraceEvents` deliberately counts. It is a
 *   statement about human movement, not a tuned number: no single squat runs three seconds
 *   from movement onset to movement end.
 *
 *   The failure it exists for is not a mistuned threshold but a **stuck state machine**.
 *   The trigger closes a window when the signal falls under [lowThreshold]; a phone lying
 *   still reads ~9.81, which sits *between* [lowThreshold] (9.7) and [highThreshold]
 *   (10.15). So a burst that ends with the phone parked — set finished, phone set down,
 *   still in hand — opens a window nothing ever closes. On Hit's trace that window ran from
 *   100.9 s to 137.8 s: **36.9 seconds** reported as one rep.
 *
 *   Note where the check sits: the window is abandoned **mid-flight**, the moment it passes
 *   the limit, not judged when it finally closes. Discarding it at close time would fix the
 *   miscount and leave the real defect, because during those 36.9 s the detector is stuck in
 *   [RepPhase.DESCENDING] and cannot start a rep at all — a live user would squat into a
 *   counter that had gone deaf. Abandoning returns it to [RepPhase.IDLE] immediately.
 * - [cooldownMs] — a new rep may not start until this long after the previous one ended,
 *   suppressing the rebound of a rep already counted. 500 ms rather than the 2000 ms this
 *   started at: see the conflation note below.
 * - [minAmplitude] — the smoothed magnitude must swing at least this far, peak to peak,
 *   across the rep window. A real squat moves the body; a wobble barely moves the signal.
 *   Both ends of the band come from Hit's recording: his softest real rep swings **1.03**
 *   and the largest non-rep inside his set — the wobble at ~63.5 s — swings **0.83**, with
 *   a handling burst at ~3.3 s at **0.86** and an unrelated drift window in Mohit's
 *   normal-pace trace at **0.63**. Replaying all three traces across the range puts the
 *   passing band at **0.87 to 1.02**, and 0.94 sits at its centre: **0.11 above** the
 *   largest non-rep and **0.09 below** the softest genuine rep.
 *
 *   The previous 0.75 was derived when Hit's trace ran through a 430 ms filter, where the
 *   same wobble swung 0.60 against a 0.92 softest rep. Normalising the filter to 250 ms let
 *   more of both signals through — the wobble rose 0.60 -> 0.83, the softest rep
 *   0.92 -> 1.03 — so the whole band moved up and 0.75 fell out of the bottom of it. That
 *   is the cost of the filter change, paid here rather than left as a silent miscount.
 *
 * ## Why duration is no longer a second independent guard
 * This section used to explain why amplitude and duration were two guards and not one. At
 * 250 ms of smoothing that is no longer true, and the loss is the real price of making the
 * filter rate-invariant.
 * At 430 ms of smoothing Hit's wobble ran **501 ms**, comfortably under the 615 ms shortest
 * genuine rep, so [minRepDurationMs] rejected it on its own merits. The wobble failed both
 * tests — too short *and* too small — and because the two guards were independent, neither
 * had to sit near the edge of its band to do the job.
 *
 * **That redundancy is gone.** The sharper filter no longer rounds the wobble's shoulders
 * off, so the same event now holds its window open for **743 ms** — *longer* than the
 * shortest genuine rep in the corpus (**615 ms**, Mohit at normal pace). The two
 * populations have crossed over on this axis: there is no value of [minRepDurationMs] that
 * rejects the wobble and keeps that rep, so duration cannot contribute to the decision at
 * all. It still bars windows too brief to be a squat, but it no longer backs amplitude up.
 *
 * So [minAmplitude] is now carrying the wobble/rep decision **alone**, on a band
 * **0.15 m/s^2 wide** (0.87 to 1.02) drawn from a **single participant**. Amplitude is also
 * the most person-dependent quantity here — the softest real rep ranges 1.03 (Hit) to 2.92
 * (Mohit, fast), a 2.8x spread — so a floor set by one soft-repping participant may not hold
 * for a fourth. This is the detector's thinnest evidence and its single point of failure.
 *
 * ### The two ways back to redundancy
 * Neither is a retune; no value of any existing parameter recovers what was lost.
 *
 * 1. **A gravity estimate**, so descent and ascent can be told apart. Magnitude is
 *    direction-blind (see the top of this doc), which is why the state machine collapses a
 *    whole rep into one undifferentiated "movement burst". Recovering direction — from the
 *    gyroscope, or by low-pass-filtering the accelerometer into a gravity vector and
 *    projecting onto it — gives a genuinely independent axis: a real squat is a *down then
 *    up* that a wobble is not, whatever its amplitude or duration.
 * 2. **More participant data.** Both edges of the 0.15 band are Hit's, and one recording
 *    cannot tell a real threshold from an accident of one person's technique. More
 *    recordings either widen the band into something defensible or show that no fixed
 *    amplitude floor generalises — and that second answer is worth having early, because it
 *    argues for per-user calibration rather than a constant.
 *
 * ## Why the cooldown dropped from 2000 ms to 500 ms
 * [cooldownMs] was silently doing two jobs: suppressing rebound, and — as a side effect of
 * being long — swallowing small wobbles that no other guard rejected. That conflation is
 * invisible until someone squats quickly. On a ~1.9 s-per-rep set, a 2000 ms cooldown
 * discards genuine reps that begin under 2 s after the previous one ended: it found 5 of
 * 10. No single cooldown value fixed it — the fast set needs 900 ms or less, while a
 * Hit's trace needs 1000 ms or more to keep a wobble out, and those two requirements do
 * not overlap.
 *
 * The fix was to split the jobs rather than to retune the number. With [minAmplitude]
 * rejecting wobbles on their merits, the cooldown is free to be only what its name says.
 * 500 ms rather than 900 ms because 900 leaves no headroom above the fast set's tightest
 * genuine gap of **917 ms** — anyone quicker would break it again.
 *
 * ## Timing and amplitude
 * `startMs` is the timestamp of the frame that crossed [highThreshold]; `endMs` is the frame
 * that fell back under [lowThreshold]. `amplitude` is the peak-to-peak swing of the
 * **smoothed** magnitude across that window — a rough proxy for how forceful the rep was.
 * It is measured on the smoothed signal on purpose, so one noise spike cannot inflate it.
 *
 * Frames must arrive in chronological order, which the filter's time-based eviction now
 * relies on as well as the state machine does. `tMillis` should come from a monotonic
 * clock; a wall clock stepped backwards mid-set would stall eviction, and therefore
 * lengthen the filter, for the length of the jump.
 *
 * Pure Kotlin, no Android dependencies. Instances are stateful and not thread-safe: use one
 * detector per workout session and feed it frames in chronological order.
 *
 * @param highThreshold m/s^2 the smoothed magnitude must exceed to open a rep. Default 10.15,
 *   tuned on a real 10-squat pocket recording.
 * @param lowThreshold m/s^2 the smoothed magnitude must fall under to close a rep. Default
 *   9.7, just below resting gravity.
 * @param minRepDurationMs minimum open-to-close time for a rep to count. Default 550 ms,
 *   65 ms below the shortest real rep recorded (615 ms).
 * @param maxRepDurationMs how long a window may stay open before it is abandoned unread.
 *   Default 3000 ms, near twice the longest rep recorded (1573 ms). Must exceed
 *   [minRepDurationMs].
 * @param cooldownMs minimum quiet time between the end of one counted rep and the start of
 *   the next. Default 500 ms, leaving headroom above the fastest recorded gap of 917 ms.
 * @param minAmplitude minimum peak-to-peak swing of the smoothed magnitude, in m/s^2, for a
 *   rep to count. Default 0.94, the centre of the 0.87-1.02 band measured at a 250 ms
 *   filter. This guard now separates wobbles from reps unaided — see the redundancy note
 *   above before changing it.
 * @param smoothingWindowMs length of the moving-average filter **in milliseconds**. Default
 *   250 ms, the length every threshold above was measured against. The sample count is
 *   derived from the observed frame timing, so this stays 250 ms of signal at any rate.
 */
class SquatRepDetector(
    private val highThreshold: Float = DEFAULT_HIGH_THRESHOLD,
    private val lowThreshold: Float = DEFAULT_LOW_THRESHOLD,
    private val minRepDurationMs: Long = DEFAULT_MIN_REP_DURATION_MS,
    private val maxRepDurationMs: Long = DEFAULT_MAX_REP_DURATION_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val minAmplitude: Float = DEFAULT_MIN_AMPLITUDE,
    private val smoothingWindowMs: Long = DEFAULT_SMOOTHING_WINDOW_MS
) {

    /**
     * Builds a detector from a user's [CalibrationProfile], falling back to the tuned
     * defaults for anyone who has not calibrated yet.
     *
     * Only the four thresholds calibration can measure are taken from the profile.
     * [highThreshold] and [lowThreshold] are deliberately **not** calibrated: they are
     * positions relative to gravity (~9.81 m/s^2), which is the same for every phone and
     * every person, so there is nothing personal in them to derive. [smoothingWindowMs] is
     * not calibrated either — it is a property of the signal rather than of the user, it is
     * already rate-invariant, and changing it per user would move every amplitude the
     * profile had just been measured in.
     *
     * @param profile the user's calibration, or null to use the tuned defaults unchanged.
     */
    constructor(profile: CalibrationProfile?) : this(
        minRepDurationMs = profile?.minRepDurationMs ?: DEFAULT_MIN_REP_DURATION_MS,
        maxRepDurationMs = profile?.maxRepDurationMs ?: DEFAULT_MAX_REP_DURATION_MS,
        cooldownMs = profile?.cooldownMs ?: DEFAULT_COOLDOWN_MS,
        minAmplitude = profile?.minAmplitude ?: DEFAULT_MIN_AMPLITUDE
    )

    init {
        require(smoothingWindowMs >= 1L) { "smoothingWindowMs must be at least 1 ms" }
        require(minAmplitude >= 0f) { "minAmplitude cannot be negative" }
        require(maxRepDurationMs > minRepDurationMs) {
            "maxRepDurationMs ($maxRepDurationMs) must sit above minRepDurationMs " +
                "($minRepDurationMs), or no window could ever satisfy both"
        }
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
    // A circular buffer of the samples currently inside the trailing time window, with
    // their timestamps and a running sum, so each frame costs O(1) instead of re-summing
    // the window. Timestamps and samples live in primitive arrays rather than a deque of
    // objects: the buffer grows once to fit whatever rate the stream delivers and then
    // allocates nothing per frame, which matters on the Android sensor callback.
    private var windowTimes = LongArray(INITIAL_WINDOW_CAPACITY)
    private var windowSamples = FloatArray(INITIAL_WINDOW_CAPACITY)
    private var windowHead = 0 // index of the oldest sample still inside the window
    private var windowCount = 0 // samples currently inside the window
    private var windowSum = 0.0

    /**
     * How many samples the most recent average was taken over: the filter length the
     * observed frame rate worked out to. Exposed so a stream running at an unexpected rate
     * is visible in a log, rather than showing up later as an unexplained miscount.
     */
    val smoothingSampleCount: Int get() = windowCount

    /**
     * Frame rate implied by the timestamps currently inside the filter window, in Hz, or
     * `0` until two frames have arrived. A debugging readout only: detection never consults
     * it, because the filter evicts on the timestamps directly rather than on an estimate.
     */
    val observedSampleRateHz: Float
        get() {
            if (windowCount < 2) return 0f
            val newest = windowTimes[(windowHead + windowCount - 1) % windowTimes.size]
            val span = newest - windowTimes[windowHead]
            return if (span <= 0L) 0f else (windowCount - 1) * 1000f / span
        }

    // --- Rep-in-progress state --------------------------------------------------------
    private var repStartMs: Long = 0L
    private var minSmoothed: Float = Float.MAX_VALUE
    private var maxSmoothed: Float = -Float.MAX_VALUE

    /** Timestamp at which the last *counted* rep ended; null until one has been counted. */
    private var lastRepEndMs: Long? = null

    /**
     * Set when a window is abandoned for running past [maxRepDurationMs], and cleared once
     * the smoothed signal drops back under [lowThreshold]. While it is set no new window may
     * open, so one long burst yields one abandonment rather than a train of them.
     */
    private var awaitingLowCrossing = false

    /**
     * Feeds one frame into the filter and the state machine.
     *
     * @return the [RepEvent] if this frame completed a rep that passed every guard, or
     *   `null` otherwise — including when a rep window closed but was rejected by a guard.
     */
    fun process(frame: MotionFrame): RepEvent? {
        val smoothed = smooth(magnitudeOf(frame), frame.tMillis)
        smoothedMagnitude = smoothed

        when (phase) {
            RepPhase.IDLE -> {
                if (awaitingLowCrossing) {
                    // A window was abandoned while the signal was still elevated. Opening a
                    // new one off the same unbroken burst would just abandon it again every
                    // maxRepDurationMs, so wait for the signal to come all the way back down
                    // first. Same discipline as the Schmitt trigger: returning to the "off"
                    // level is what re-arms the "on" one.
                    if (smoothed < lowThreshold) awaitingLowCrossing = false
                } else if (smoothed > highThreshold) {
                    // Movement burst begins: open a rep window and start tracking its swing.
                    phase = RepPhase.DESCENDING
                    repStartMs = frame.tMillis
                    minSmoothed = smoothed
                    maxSmoothed = smoothed
                }
            }

            RepPhase.DESCENDING -> {
                trackExtremes(smoothed)
                if (frame.tMillis - repStartMs > maxRepDurationMs) {
                    // Open too long to be a squat: abandon the window rather than wait for a
                    // close that may never come. Checked before the close test, so a window
                    // past the limit can never fall through and be emitted as a rep.
                    phase = RepPhase.IDLE
                    awaitingLowCrossing = true
                    resetRepWindow()
                    return null
                }
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
        awaitingLowCrossing = false
        smoothedMagnitude = 0f
        // Nothing past windowCount is ever read, so emptying the filter is these three
        // lines. The arrays keep the capacity they grew to, which is already the right
        // size for the stream this detector is being reused on.
        windowHead = 0
        windowCount = 0
        windowSum = 0.0
    }

    /** Orientation-invariant size of the acceleration vector, gravity included. */
    private fun magnitudeOf(frame: MotionFrame): Float =
        sqrt(frame.ax * frame.ax + frame.ay * frame.ay + frame.az * frame.az)

    /**
     * Pushes one sample through the moving average and returns the new mean.
     *
     * The window keeps every sample less than [smoothingWindowMs] old, so its length *in
     * samples* is whatever the stream's rate makes it while its length *in time* is fixed.
     * The sample just added is zero milliseconds old, so at least one always survives
     * eviction and the divide below cannot be by zero. Until the window fills — the first
     * [smoothingWindowMs] of a session — the mean is taken over the samples so far.
     *
     * The running sum is a `Double` so thousands of float additions cannot drift.
     *
     * @param tMillis the frame's timestamp; the window is measured against it.
     */
    private fun smooth(sample: Float, tMillis: Long): Float {
        if (windowCount == windowTimes.size) growWindow()

        val tail = (windowHead + windowCount) % windowTimes.size
        windowTimes[tail] = tMillis
        windowSamples[tail] = sample
        windowSum += sample
        windowCount++

        while (tMillis - windowTimes[windowHead] >= smoothingWindowMs) {
            windowSum -= windowSamples[windowHead]
            windowHead = (windowHead + 1) % windowTimes.size
            windowCount--
        }

        return (windowSum / windowCount).toFloat()
    }

    /**
     * Doubles the filter buffer, unrolling the circular contents to start at index 0.
     *
     * Reached only when one window's worth of samples outgrows the current capacity — that
     * is, on a stream faster than [INITIAL_WINDOW_CAPACITY] samples per window — and then
     * not again until the next doubling.
     */
    private fun growWindow() {
        val grownTimes = LongArray(windowTimes.size * 2)
        val grownSamples = FloatArray(grownTimes.size)
        for (i in 0 until windowCount) {
            val from = (windowHead + i) % windowTimes.size
            grownTimes[i] = windowTimes[from]
            grownSamples[i] = windowSamples[from]
        }
        windowTimes = grownTimes
        windowSamples = grownSamples
        windowHead = 0
    }

    /**
     * Applies the duration, amplitude and cooldown guards to the rep window that just closed.
     *
     * A rejected window deliberately leaves [lastRepEndMs] untouched: a window that was not
     * a rep must not start the cooldown for the rep that follows it.
     *
     * @return the recorded [RepEvent], or `null` if a guard rejected the window.
     */
    private fun closeRepWindow(endMs: Long): RepEvent? {
        if (endMs - repStartMs < minRepDurationMs) return null // too brief to be a squat

        val amplitude = maxSmoothed - minSmoothed
        if (amplitude < minAmplitude) return null // too small a swing to be a rep

        val previousEnd = lastRepEndMs
        if (previousEnd != null && repStartMs - previousEnd < cooldownMs) {
            return null // too soon after the last rep — almost certainly its rebound
        }

        val event = RepEvent(
            index = _reps.size,
            startMs = repStartMs,
            endMs = endMs,
            amplitude = amplitude
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

    companion object {
        // The tuned defaults, named so a CalibrationProfile can fall back to exactly these
        // values rather than repeating the literals. Every figure and its justification is
        // in the guards section of this class's documentation; these are unchanged.
        const val DEFAULT_HIGH_THRESHOLD = 10.15f
        const val DEFAULT_LOW_THRESHOLD = 9.7f
        const val DEFAULT_MIN_REP_DURATION_MS = 550L
        const val DEFAULT_MAX_REP_DURATION_MS = 3000L
        const val DEFAULT_COOLDOWN_MS = 500L
        const val DEFAULT_MIN_AMPLITUDE = 0.94f
        const val DEFAULT_SMOOTHING_WINDOW_MS = 250L

        /**
         * Starting size of the filter buffer: enough for a 250 ms window at 128 Hz, so none
         * of the phone rates seen so far (50-100 Hz) trigger a resize at all.
         */
        private const val INITIAL_WINDOW_CAPACITY = 32
    }
}
