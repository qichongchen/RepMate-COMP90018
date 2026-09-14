package com.repmate.engine

/**
 * A recorded trace paired with its ground truth, plus the slicing helpers tests and the
 * demo need.
 *
 * Slicing is done here rather than in each test so that "the set window" means exactly one
 * thing across the whole suite.
 *
 * @property name the recording's base file name, e.g. `squat_10_pocket`. Used in assertion
 *   messages so a failure names the trace that caused it.
 * @property frames every frame in the recording, in chronological order.
 * @property expectation what the human actually performed.
 */
data class LabelledTrace(
    val name: String,
    val frames: List<MotionFrame>,
    val expectation: TraceExpectation
) {
    /**
     * The frames covering the actual set, with phone-handling at either end excluded.
     *
     * Both bounds are inclusive: a rep exactly on the boundary belongs to the set.
     */
    fun setWindow(): List<MotionFrame> =
        frames.filter { it.tMillis in expectation.setStartMs..expectation.setEndMs }

    /**
     * The frames covering the declared still period, or null when the recording has none.
     *
     * Used as the false-positive check: a phone lying still reads roughly gravity, which
     * must never cross the detector's high threshold.
     */
    fun quietWindow(): List<MotionFrame>? {
        val start = expectation.quietStartMs ?: return null
        val end = expectation.quietEndMs ?: return null
        return frames.filter { it.tMillis in start..end }
    }

    /**
     * Runs the detector this recording's exercise calls for, and returns the reps it found.
     *
     * ## Why the choice lives here
     * Every trace test iterates the whole library, so each one needs "the right detector for
     * this recording" in exactly the same way. Before this existed the tests all hardcoded
     * [SquatRepDetector], which meant the `exercise` key in a `.expect` file was parsed,
     * validated against [ExerciseType] — and then ignored. A jumping-jack recording dropped
     * into `traces/` would have been replayed through the squat detector, and the failure
     * would have looked like a threshold problem rather than a wiring one.
     *
     * Putting the choice beside [setWindow] keeps the rule that this class is where "what
     * this recording means" is decided once for the whole suite.
     *
     * A fresh detector is built per call on purpose: these are stateful, and several tests
     * replay the same trace twice to check determinism.
     *
     * @param frames the slice to replay — usually [setWindow], [quietWindow] or all [frames].
     */
    fun detect(frames: List<MotionFrame>): List<RepEvent> = when (expectation.exercise) {
        ExerciseType.SQUAT -> SquatRepDetector().processAll(frames)
        ExerciseType.JUMPING_JACK -> JumpingJackRepDetector().processAll(frames)
        // Deliberately fatal rather than falling back to a squat detector. A silent
        // fallback would report a plausible-looking count for an exercise nothing has been
        // written for, which is the failure the .expect format exists to prevent.
        ExerciseType.PUSHUP -> error(
            "No detector for ${expectation.exercise} yet (trace '$name'). Add one, or remove " +
                "the recording until there is one."
        )
    }

    /** Length of the recording in seconds, for reporting. */
    fun durationSeconds(): Double =
        if (frames.isEmpty()) 0.0 else frames.last().tMillis / 1000.0

    /** Approximate sample rate in Hz, for reporting. */
    fun sampleRateHz(): Double {
        val duration = durationSeconds()
        return if (duration <= 0.0) 0.0 else frames.size / duration
    }
}
