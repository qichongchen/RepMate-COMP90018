package engine

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

    /** Length of the recording in seconds, for reporting. */
    fun durationSeconds(): Double =
        if (frames.isEmpty()) 0.0 else frames.last().tMillis / 1000.0

    /** Approximate sample rate in Hz, for reporting. */
    fun sampleRateHz(): Double {
        val duration = durationSeconds()
        return if (duration <= 0.0) 0.0 else frames.size / duration
    }
}
