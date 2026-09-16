package com.repmate.engine

/**
 * TEMPORARY fake push-up rep counter, standing in for a real signal-processing detector that
 * does not exist yet -- see [SquatRepDetector]/[JumpingJackRepDetector] for what a real one
 * looks like here. [com.repmate.ui.workout.LiveWorkoutViewModel] drives it through exactly the
 * same [process] shape those two expose, so replacing this file's internals with genuine
 * push-up detection is the only change a real detector needs -- no call site has to change.
 *
 * TODO(engine): replace with a real push-up detector once the signal is characterised (a
 * push-up's magnitude/orientation signature is an open question for Mohit, unlike squat's or
 * jumping jack's, which both have recorded traces behind them). Until then this fabricates one
 * rep every [fakeRepIntervalMs] of elapsed frame time -- driven by the timestamps in the frames
 * it's fed, not a wall-clock timer, so it still respects Live Workout pausing frames the same
 * way a real detector would.
 *
 * Pure Kotlin, no Android dependencies, same as every other class in this package.
 *
 * @param fakeRepIntervalMs how much elapsed frame time fabricates one rep. Default 1500 ms, a
 *   plausible push-up pace -- not measured against anything, since there is no real signal here
 *   to measure.
 */
class PushupRepDetector(
    private val fakeRepIntervalMs: Long = DEFAULT_FAKE_REP_INTERVAL_MS,
) {
    private val _reps = mutableListOf<RepEvent>()

    /** Reps detected so far, in the order they completed. */
    val reps: List<RepEvent> get() = _reps

    /** How many full reps have been counted so far. */
    val repCount: Int get() = _reps.size

    /** Always [RepPhase.IDLE]: this stub does not model a movement cycle at all. */
    val phase: RepPhase = RepPhase.IDLE

    private var windowStartMs: Long? = null

    /**
     * Feeds one frame in.
     *
     * @return a fabricated [RepEvent] once [fakeRepIntervalMs] of frame time has elapsed since
     *   the last one (or since the first frame fed in), or `null` otherwise.
     */
    fun process(frame: MotionFrame): RepEvent? {
        val start = windowStartMs ?: frame.tMillis.also { windowStartMs = it }
        if (frame.tMillis - start < fakeRepIntervalMs) return null

        windowStartMs = frame.tMillis
        val event = RepEvent(index = _reps.size, startMs = start, endMs = frame.tMillis, amplitude = 0f)
        _reps += event
        return event
    }

    /** Clears detected reps and forgets the current fake window's start time. */
    fun reset() {
        _reps.clear()
        windowStartMs = null
    }

    companion object {
        const val DEFAULT_FAKE_REP_INTERVAL_MS = 1500L
    }
}
