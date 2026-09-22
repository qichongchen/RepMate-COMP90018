package com.repmate.ui.calibration

import com.repmate.engine.CalibrationOutcome
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.LabelledTrace
import com.repmate.engine.MotionFrame
import com.repmate.engine.TraceLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CalibrationCapture] replayed against every recorded trace: the same path the calibration
 * screen runs on device, minus the sensor and the clock.
 */
class CalibrationCaptureSessionTest {

    private val traces: List<LabelledTrace> by lazy { TraceLibrary.loadAll() }

    /** Feeds [frames] until the capture completes, returning how many frames it took. */
    private fun CalibrationCapture.feedUntilComplete(frames: List<MotionFrame>): Int {
        frames.forEachIndexed { i, frame ->
            onFrame(frame)
            if (isComplete) return i + 1
        }
        return frames.size
    }

    @Test
    fun `every squat and jumping-jack trace calibrates from its set window`() {
        val calibratable = traces.filter { CalibrationCapture.isSupported(it.expectation.exercise) }
        assertEquals(6, calibratable.size, "four squat and two jumping-jack traces")

        for (trace in calibratable) {
            val capture = CalibrationCapture(trace.expectation.exercise)
            capture.feedUntilComplete(trace.setWindow())

            assertTrue(capture.isComplete, "${trace.name}: only ${capture.reps.size} reps captured")
            assertEquals(CalibrationProfile.REQUIRED_SAMPLES, capture.reps.size, trace.name)
            assertIs<CalibrationOutcome.Accepted>(capture.evaluate(), "${trace.name}: ${capture.evaluate()}")
        }
    }

    @Test
    fun `frames after the fifth rep are ignored`() {
        val trace = traces.first { it.name == "squat_10_pocket" }
        val capture = CalibrationCapture(ExerciseType.SQUAT)
        val set = trace.setWindow()
        val used = capture.feedUntilComplete(set)
        val fifth = capture.reps.last()

        // The rest of the set holds five more real reps; none may be added.
        set.drop(used).forEach { assertNull(capture.onFrame(it)) }
        assertEquals(CalibrationProfile.REQUIRED_SAMPLES, capture.reps.size)
        assertEquals(fifth, capture.reps.last())
        assertFalse(capture.isMoving, "a finished capture must not report movement")
    }

    @Test
    fun `too few reps is rejected, not saved`() {
        // Four reps in, then the user stops: evaluate must refuse rather than derive from four.
        val trace = traces.first { it.name == "squat_10_pocket" }
        val capture = CalibrationCapture(ExerciseType.SQUAT)
        for (frame in trace.setWindow()) {
            capture.onFrame(frame)
            if (capture.reps.size == 4) break
        }
        val outcome = assertIs<CalibrationOutcome.Rejected>(capture.evaluate())
        assertEquals(CalibrationOutcome.Reason.TOO_FEW_SAMPLES, outcome.reason)
    }

    @Test
    fun `a still phone captures nothing`() {
        val quiet = traces.first { it.name == "squat_10_hit" }.quietWindow()!!
        val capture = CalibrationCapture(ExerciseType.SQUAT)
        quiet.forEach { capture.onFrame(it) }
        assertEquals(0, capture.reps.size)
    }

    @Test
    fun `push-ups cannot be calibrated`() {
        assertFalse(CalibrationCapture.isSupported(ExerciseType.PUSHUP))
        assertFailsWith<IllegalArgumentException> { CalibrationCapture(ExerciseType.PUSHUP) }
    }
}
