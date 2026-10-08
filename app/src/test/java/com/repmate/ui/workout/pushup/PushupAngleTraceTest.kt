package com.repmate.ui.workout.pushup

import com.repmate.data.memory.PushupRepTrace
import com.repmate.engine.PushupRepDetector.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PushupAngleTrace] is what gives a finished push-up set a per-rep curve to report, so these
 * cover where a rep's window starts and stops and what gets dropped.
 */
class PushupAngleTraceTest {
    private val trace = PushupAngleTrace()

    @Test
    fun aRepsCurveRunsFromTheFrameTheArmFirstReadsAsBentToTheFrameThatCompletesIt() {
        // Setting up with a straight arm: not part of any rep.
        trace.onFrame(nowMs = 0, phase = Phase.UP, smoothedDegrees = 170.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 100, phase = Phase.UP, smoothedDegrees = 168.0, repCompleted = false, bottomDegrees = null)
        // Down: the rep starts here.
        trace.onFrame(nowMs = 200, phase = Phase.DOWN, smoothedDegrees = 120.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 300, phase = Phase.DOWN, smoothedDegrees = 95.0, repCompleted = false, bottomDegrees = null)
        // Back to straight: the rep completes.
        trace.onFrame(nowMs = 400, phase = Phase.UP, smoothedDegrees = 160.0, repCompleted = true, bottomDegrees = 95.0)

        val rep = trace.reps.single()
        assertEquals(1, rep.repIndex)
        assertEquals(95.0, rep.bottomDegrees!!, 0.001)
        // Rebased to this rep's own start, and the setup frames are not in it.
        assertEquals(listOf(0L to 120f, 100L to 95f, 200L to 160f), rep.curve)
    }

    @Test
    fun framesBetweenRepsAreNotPartOfEitherRep() {
        oneRep(startMs = 0)
        // Resting, arm straight.
        trace.onFrame(nowMs = 500, phase = Phase.UP, smoothedDegrees = 170.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 600, phase = Phase.UP, smoothedDegrees = 171.0, repCompleted = false, bottomDegrees = null)
        oneRep(startMs = 1_000)

        assertEquals(2, trace.reps.size)
        // The second rep's curve starts at its own first bent frame, not at the rest frames.
        assertEquals(0L, trace.reps[1].curve.first().first)
        assertEquals(3, trace.reps[1].curve.size)
    }

    @Test
    fun repsAreNumberedFromOneInOrder() {
        oneRep(startMs = 0)
        oneRep(startMs = 1_000)
        oneRep(startMs = 2_000)

        assertEquals(listOf(1, 2, 3), trace.reps.map { it.repIndex })
    }

    @Test
    fun framesWithNoReadableAngleAreSkippedWithoutBreakingTheRep() {
        trace.onFrame(nowMs = 0, phase = Phase.DOWN, smoothedDegrees = 120.0, repCompleted = false, bottomDegrees = null)
        // Nobody in frame, or the locked arm hidden: no angle this frame.
        trace.onFrame(nowMs = 100, phase = Phase.DOWN, smoothedDegrees = null, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 200, phase = Phase.DOWN, smoothedDegrees = 100.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 300, phase = Phase.UP, smoothedDegrees = 160.0, repCompleted = true, bottomDegrees = 100.0)

        assertEquals(listOf(0L to 120f, 200L to 100f, 300L to 160f), trace.reps.single().curve)
    }

    @Test
    fun aRepWithNoReadableAnglesAtAllStillCounts() {
        // The rep happened and is scored; there is simply no curve to draw for it.
        trace.onFrame(nowMs = 0, phase = Phase.DOWN, smoothedDegrees = null, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 100, phase = Phase.UP, smoothedDegrees = null, repCompleted = true, bottomDegrees = null)

        val rep = trace.reps.single()
        assertEquals(1, rep.repIndex)
        assertTrue(rep.curve.isEmpty())
        assertNull(rep.bottomDegrees)
    }

    @Test
    fun aRepLeftOpenIsNotReported() {
        // Went down and never came back up -- the detector never counted it, so neither does this.
        trace.onFrame(nowMs = 0, phase = Phase.DOWN, smoothedDegrees = 120.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 100, phase = Phase.DOWN, smoothedDegrees = 95.0, repCompleted = false, bottomDegrees = null)

        assertEquals(emptyList<PushupRepTrace>(), trace.reps)
    }

    @Test
    fun aSecondDescentBeforeAnyRepCompletesReplacesTheFirstPartialOne() {
        // The detector re-entering DOWN without completing means the previous descent went
        // nowhere (a long pause resets it), so its samples are not this rep's.
        trace.onFrame(nowMs = 0, phase = Phase.DOWN, smoothedDegrees = 120.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 100, phase = Phase.WAITING, smoothedDegrees = 130.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 1_000, phase = Phase.DOWN, smoothedDegrees = 118.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(nowMs = 1_100, phase = Phase.UP, smoothedDegrees = 160.0, repCompleted = true, bottomDegrees = 118.0)

        assertEquals(listOf(0L to 118f, 100L to 160f), trace.reps.single().curve)
    }

    @Test
    fun aRepsSamplesAreCapped() {
        val capped = PushupAngleTrace(maxSamplesPerRep = 3)
        capped.onFrame(0, Phase.DOWN, 120.0, repCompleted = false, bottomDegrees = null)
        repeat(10) { i -> capped.onFrame(100L * (i + 1), Phase.DOWN, 110.0, repCompleted = false, bottomDegrees = null) }
        capped.onFrame(5_000, Phase.UP, 160.0, repCompleted = true, bottomDegrees = 110.0)

        // Stops collecting rather than growing: someone who walks away mid-rep cannot fill the heap.
        assertEquals(3, capped.reps.single().curve.size)
    }

    @Test
    fun resetForgetsEverything() {
        oneRep(startMs = 0)
        trace.reset()

        assertEquals(emptyList<PushupRepTrace>(), trace.reps)

        // And the next rep after a reset is numbered 1 again.
        oneRep(startMs = 1_000)
        assertEquals(listOf(1), trace.reps.map { it.repIndex })
    }

    /** One straight-bent-straight rep, three usable frames, starting at [startMs]. */
    private fun oneRep(startMs: Long) {
        trace.onFrame(startMs, Phase.DOWN, 120.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(startMs + 100, Phase.DOWN, 95.0, repCompleted = false, bottomDegrees = null)
        trace.onFrame(startMs + 200, Phase.UP, 160.0, repCompleted = true, bottomDegrees = 95.0)
    }
}
