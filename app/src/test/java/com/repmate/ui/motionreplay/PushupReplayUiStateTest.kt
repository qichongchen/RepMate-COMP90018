package com.repmate.ui.motionreplay

import com.repmate.data.memory.PushupRepTrace
import com.repmate.engine.ExerciseType
import com.repmate.engine.PushupRepDetector
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pushupReplayUiState] builds the push-up report: each counted rep's elbow-angle curve beside
 * the score that rep was given.
 */
class PushupReplayUiStateTest {
    @Test
    fun everyRepIsChartedAgainstTheDetectorsStraightBentRange() {
        val state = pushupReplayUiState(session(reps = 2), traces(count = 2))!!

        assertTrue(state.isReplayAvailable)
        assertEquals(2, state.reps.size)
        val rep = state.reps.first() as MotionReplayRepUi.PushupAngle
        // The band is what the rep had to achieve: below the bent line, back above the straight one.
        assertEquals(PushupRepDetector.DEFAULT_DOWN_DEGREES.toFloat(), rep.angleBand.start, 0.001f)
        assertEquals(PushupRepDetector.DEFAULT_UP_DEGREES.toFloat(), rep.angleBand.endInclusive, 0.001f)
    }

    @Test
    fun aRepsCurveAndScoreComeFromTheSameRep() {
        val session =
            session(reps = 2).copy(
                reps =
                    listOf(
                        repScore(index = 1, score = 9f, reasons = listOf("good depth")),
                        repScore(index = 2, score = 4f, reasons = listOf("not as deep as your best rep this session")),
                    ),
            )

        val reps = pushupReplayUiState(session, traces(count = 2))!!.reps.map { it as MotionReplayRepUi.PushupAngle }

        // Pairing by position: rep 2's curve must not be shown under rep 1's score.
        assertEquals(listOf(9f, 4f), reps.map { it.score })
        assertEquals(listOf(listOf("good depth"), listOf("not as deep as your best rep this session")), reps.map { it.reasons })
        assertEquals(listOf(1, 2), reps.map { it.repIndex })
    }

    @Test
    fun theDepthReachedIsCarriedThrough() {
        val rep = pushupReplayUiState(session(reps = 1), traces(count = 1))!!.reps.single() as MotionReplayRepUi.PushupAngle

        assertEquals(95.0, rep.bottomDegrees!!, 0.001)
    }

    @Test
    fun aRepWhoseDepthCouldNotBeReadStillReports() {
        val traces = listOf(PushupRepTrace(repIndex = 1, curve = listOf(0L to 120f), bottomDegrees = null))

        val rep = pushupReplayUiState(session(reps = 1), traces)!!.reps.single() as MotionReplayRepUi.PushupAngle

        assertNull(rep.bottomDegrees)
        assertTrue(rep.curve.isNotEmpty())
    }

    @Test
    fun aSetWithNoTracesHasNoReport() {
        // Leaves the screen on its honest no-replay notice rather than an empty chart.
        assertNull(pushupReplayUiState(session(reps = 3), emptyList()))
    }

    @Test
    fun moreTracesThanScoredRepsReportsOnlyThePairedOnes() {
        // Defensive: the two are produced by the same set and should match, but a report that
        // invented a score for an unpaired curve would be showing made-up data.
        val state = pushupReplayUiState(session(reps = 1), traces(count = 3))!!

        assertEquals(1, state.reps.size)
    }

    @Test
    fun theReportIsForTheSessionItWasBuiltFrom() {
        val state = pushupReplayUiState(session(reps = 1), traces(count = 1))!!

        assertEquals("pushup-session", state.sessionId)
        assertEquals(ExerciseType.PUSHUP, state.exercise)
    }

    private fun traces(count: Int) =
        List(count) { index ->
            PushupRepTrace(
                repIndex = index + 1,
                curve = listOf(0L to 168f, 150L to 120f, 300L to 95f, 450L to 160f),
                bottomDegrees = 95.0,
            )
        }

    private fun session(reps: Int) =
        WorkoutSession(
            id = "pushup-session",
            exercise = ExerciseType.PUSHUP,
            startedAt = 1_000L,
            endedAt = 2_000L,
            reps = List(reps) { repScore(index = it + 1, score = 8f, reasons = listOf("good depth")) },
        )

    private fun repScore(
        index: Int,
        score: Float,
        reasons: List<String>,
    ) = RepScore(
        repIndex = index,
        score = score,
        tempoSeconds = 1.8f,
        rangePercent = 90,
        pauseSeconds = 0f,
        reasons = reasons,
    )
}
