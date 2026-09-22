package com.repmate.engine

/**
 * Counts push-ups from a stream of elbow angles.
 *
 * Pure Kotlin, no Android or ML Kit types: the caller (the camera/pose layer, in the app module)
 * turns a detected pose into a single angle; this class only knows about angles. That split is
 * what makes it unit-testable on the JVM and keeps `com.repmate.engine` free of camera concerns,
 * same as [SquatRepDetector] and [JumpingJackRepDetector] are free of raw sensor concerns.
 *
 * Replaces the earlier fake that invented a rep every 1.5 seconds of elapsed frame time --
 * measurements below are from a real recorded session (phone propped to the side, whole body in
 * frame), not invented.
 *
 * ## The cycle
 * ```
 *   WAITING --(straight)--> UP --(bent)--> DOWN --(straight)--> UP      one rep counted here
 * ```
 * A rep is **straight -> bent -> straight**, counted when the arm comes back to straight. Starting
 * bent counts nothing until the arm has been straight once, so picking the phone up mid-set or
 * looking at a person already at the bottom cannot invent a rep.
 *
 * ## Two thresholds, deliberately far apart
 * [upAtOrAboveDegrees] and [downAtOrBelowDegrees] form a Schmitt trigger like the IMU detectors':
 * the gap between them (126.5 to 152.6 by default, 26 degrees) is what stops an angle jittering
 * around one line from being counted as several reps. A half rep -- straight, then bent only as
 * far as 135, then straight -- never reaches the down threshold and counts nothing, which is the
 * point.
 *
 * ## Filtering: a median, not an average
 * Each angle is replaced by the **median of the last [medianWindow] angles** before it is compared.
 * A single wild frame (the pose detector briefly mislabelling a landmark, or the tracked arm
 * changing) is removed outright, whatever its size. An average only halves a spike -- one frame at
 * 40 degrees would still pull 168 down to 104 and count a rep. A real movement, held for two
 * frames, does pass. The price is a lag of one frame at the default window of 3.
 *
 * ## Where the numbers come from: one real measured session
 * Straight-arm and bent-arm readings were measured directly from a real set, not guessed from
 * "roughly 160-170 straight, roughly 90 bent": the thresholds below (152.6 / 126.5) are placed as
 * fractions of that session's own swing, the same reasoning [CalibrationProfile] uses for the IMU
 * exercises -- fractions of a measured swing rather than fixed constants, so a gap wide enough to
 * reject jitter doesn't also reject a genuinely shallow rep.
 *
 * **One person, one session, one placement (phone to the side).** Per-user calibration for
 * push-ups is the eventual replacement for these constants, not a tuned value meant to hold for
 * everyone -- calibration is deliberately skipped for push-ups for now (see
 * `PushupWorkoutViewModel`), so this is what ships until that lands.
 *
 * @param upAtOrAboveDegrees smoothed angle at or above which the arm counts as straight.
 * @param downAtOrBelowDegrees smoothed angle at or below which the arm counts as bent.
 * @param medianWindow how many recent angles the median is taken over; odd and at least 1, where 1
 *   disables filtering.
 */
class PushupRepDetector(
    private val upAtOrAboveDegrees: Double = DEFAULT_UP_DEGREES,
    private val downAtOrBelowDegrees: Double = DEFAULT_DOWN_DEGREES,
    private val medianWindow: Int = DEFAULT_MEDIAN_WINDOW,
) {
    init {
        require(downAtOrBelowDegrees < upAtOrAboveDegrees) {
            "the bent threshold ($downAtOrBelowDegrees) must sit below the straight one ($upAtOrAboveDegrees)"
        }
        require(medianWindow >= 1 && medianWindow % 2 == 1) { "medianWindow must be odd and at least 1, got $medianWindow" }
    }

    /** The most recent valid angles, oldest first, at most [medianWindow] of them. */
    private val recent = ArrayDeque<Double>()

    /** Where in the cycle the arm is. [Phase.WAITING] until it has first been straight. */
    enum class Phase { WAITING, UP, DOWN }

    /**
     * A change of [Phase].
     *
     * @property smoothedDegrees the filtered angle that caused it, for logging.
     * @property repCompleted true when this change finished a rep (DOWN back to UP).
     */
    data class Transition(
        val from: Phase,
        val to: Phase,
        val smoothedDegrees: Double,
        val repCompleted: Boolean,
    )

    /** Reps counted so far. */
    var count: Int = 0
        private set

    var phase: Phase = Phase.WAITING
        private set

    /** The latest filtered angle (the median), or null before any valid angle has arrived. */
    var smoothedDegrees: Double? = null
        private set

    /**
     * Feeds one elbow angle in.
     *
     * @param angleDegrees the angle in degrees, or null when there was none this frame (no person
     *   in frame, or the locked arm not visible). Null and non-finite values are ignored -- they
     *   neither advance the state nor reset it, so a frame with no pose does not lose a rep in
     *   progress.
     * @return the [Transition] this angle caused, or null if the phase did not change.
     */
    fun update(angleDegrees: Double?): Transition? {
        if (angleDegrees == null || !angleDegrees.isFinite()) return null

        recent.addLast(angleDegrees)
        if (recent.size > medianWindow) recent.removeFirst()
        val smoothed =
            recent.sorted().let { sorted ->
                val mid = sorted.size / 2
                if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
            }
        smoothedDegrees = smoothed

        val from = phase
        val to =
            when (from) {
                Phase.WAITING -> if (smoothed >= upAtOrAboveDegrees) Phase.UP else Phase.WAITING
                Phase.UP -> if (smoothed <= downAtOrBelowDegrees) Phase.DOWN else Phase.UP
                Phase.DOWN -> if (smoothed >= upAtOrAboveDegrees) Phase.UP else Phase.DOWN
            }
        if (to == from) return null

        phase = to
        val repCompleted = from == Phase.DOWN && to == Phase.UP
        if (repCompleted) count++
        return Transition(from, to, smoothed, repCompleted)
    }

    /** Back to zero reps and waiting for a straight arm. Used between sets and on a camera change. */
    fun reset() {
        count = 0
        phase = Phase.WAITING
        smoothedDegrees = null
        recent.clear()
    }

    companion object {
        /** Measured straight-arm threshold from a real session, phone to the side. */
        const val DEFAULT_UP_DEGREES = 152.6

        /** Measured bent-arm threshold from a real session, phone to the side. */
        const val DEFAULT_DOWN_DEGREES = 126.5

        /** Removes any single bad frame, at a cost of one frame of lag. */
        const val DEFAULT_MEDIAN_WINDOW = 3
    }
}
