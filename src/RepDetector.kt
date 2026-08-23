package engine

/**
 * Counts squat repetitions from the vertical acceleration channel ([MotionFrame.ay]).
 *
 * ## The signal
 * With the phone held or pocketed upright, `ay` rests at roughly gravity (~9.8 m/s^2).
 * One squat produces a single dip-then-spike around that resting value: the body
 * accelerates downward on the way down (`ay` drops), then the drive out of the bottom
 * pushes `ay` well above gravity, and finally the signal settles back near 9.8 while
 * standing.
 *
 * ## The state machine
 * Frames are fed in one at a time via [process], which advances a [RepPhase]:
 *
 * ```
 *   IDLE --------- ay < downThreshold --------> DESCENDING
 *   DESCENDING --- ay > upThreshold ----------> ASCENDING
 *   ASCENDING ---- downThreshold <= ay <= upThreshold --> IDLE   (emit a RepEvent)
 * ```
 *
 * A rep is only counted on the full round trip, so a dip that never produces an
 * upward drive — or a drive that never settles — leaves the detector parked in its
 * current phase rather than counting.
 *
 * [RepPhase.BOTTOM] and [RepPhase.TOP] are part of the shared enum but are not yet
 * entered by this detector; they are reserved for the dwell/hold detection that comes
 * with the false-positive guards.
 *
 * ## Timing and amplitude
 * A rep's `startMs` is the timestamp of the frame that triggered the descent and its
 * `endMs` is the timestamp of the frame where the signal settled. `amplitude` is the
 * peak-to-peak swing (largest `ay` minus smallest `ay`) observed over that window,
 * which acts as a rough proxy for how forceful the rep was.
 *
 * This first version deliberately has **no false-positive guards** — no minimum
 * duration, no minimum amplitude, no debounce. Noise spikes that cross both
 * thresholds in order will be counted; that hardening is the next step.
 *
 * Pure Kotlin, no Android dependencies. Instances are stateful and not thread-safe:
 * use one detector per workout session, feeding it frames in chronological order.
 *
 * @param downThreshold `ay` must fall **below** this (m/s^2) to start a descent.
 *   Default 7.0 — comfortably under resting gravity, so ordinary noise won't trip it.
 * @param upThreshold `ay` must rise **above** this (m/s^2) during the drive out of the
 *   bottom to enter the ascent. Default 12.0 — comfortably above resting gravity.
 */
class SquatRepDetector(
    private val downThreshold: Float = 7.0f,
    private val upThreshold: Float = 12.0f
) {

    private val _reps = mutableListOf<RepEvent>()

    /** Reps detected so far, in the order they completed. */
    val reps: List<RepEvent> get() = _reps

    /** How many full reps have been counted so far. */
    val repCount: Int get() = _reps.size

    /** The phase the detector is currently in. Starts at [RepPhase.IDLE]. */
    var phase: RepPhase = RepPhase.IDLE
        private set

    // Running stats for the rep currently in progress.
    private var repStartMs: Long = 0L
    private var minAy: Float = Float.MAX_VALUE
    private var maxAy: Float = -Float.MAX_VALUE

    /**
     * Feeds one frame into the state machine.
     *
     * @return the [RepEvent] if this frame completed a rep, or `null` otherwise.
     */
    fun process(frame: MotionFrame): RepEvent? {
        val ay = frame.ay

        when (phase) {
            RepPhase.IDLE -> {
                if (ay < downThreshold) {
                    // Start of a descent: open a new rep window.
                    phase = RepPhase.DESCENDING
                    repStartMs = frame.tMillis
                    minAy = ay
                    maxAy = ay
                }
            }

            RepPhase.DESCENDING -> {
                trackExtremes(ay)
                if (ay > upThreshold) {
                    // The drive out of the bottom — the "push up" phase.
                    phase = RepPhase.ASCENDING
                }
            }

            RepPhase.ASCENDING -> {
                trackExtremes(ay)
                if (ay in downThreshold..upThreshold) {
                    // Settled back near gravity: the rep is complete.
                    val event = RepEvent(
                        index = _reps.size,
                        startMs = repStartMs,
                        endMs = frame.tMillis,
                        amplitude = maxAy - minAy
                    )
                    _reps += event
                    resetRepWindow()
                    phase = RepPhase.IDLE
                    return event
                }
            }

            // Not entered yet; kept exhaustive so adding transitions later is a compile-time prompt.
            RepPhase.BOTTOM, RepPhase.TOP -> Unit
        }

        return null
    }

    /**
     * Convenience helper: runs a whole trace through [process] and returns every rep
     * detected *by this call*. State carries over, so a detector can be fed in chunks.
     */
    fun processAll(frames: List<MotionFrame>): List<RepEvent> =
        frames.mapNotNull { process(it) }

    /** Clears all detected reps and returns the state machine to [RepPhase.IDLE]. */
    fun reset() {
        _reps.clear()
        resetRepWindow()
        phase = RepPhase.IDLE
    }

    private fun trackExtremes(ay: Float) {
        if (ay < minAy) minAy = ay
        if (ay > maxAy) maxAy = ay
    }

    private fun resetRepWindow() {
        repStartMs = 0L
        minAy = Float.MAX_VALUE
        maxAy = -Float.MAX_VALUE
    }
}
