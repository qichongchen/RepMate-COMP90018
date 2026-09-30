package com.repmate.ui.profile

import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Test

private fun rep(
    index: Int,
    score: Float,
) = RepScore(
    repIndex = index,
    score = score,
    tempoSeconds = 1f,
    rangePercent = 90,
    pauseSeconds = 0f,
    reasons = emptyList(),
)

private fun session(
    id: String,
    vararg scores: Float,
) = WorkoutSession(
    id = id,
    exercise = ExerciseType.SQUAT,
    startedAt = 0L,
    reps = scores.mapIndexed { index, score -> rep(index, score) },
)

class AverageRepScoreTest {
    @Test
    fun `no sessions gives 0`() {
        assertEquals(0f, averageRepScore(emptyList()), 0f)
    }

    @Test
    fun `sessions with no reps give 0`() {
        assertEquals(0f, averageRepScore(listOf(session("a"), session("b"))), 0f)
    }

    @Test
    fun `single session averages its reps`() {
        assertEquals(7f, averageRepScore(listOf(session("a", 6f, 8f))), 0.0001f)
    }

    @Test
    fun `pools every rep rather than averaging per-session averages`() {
        // Pooled: (10 + 6 + 6 + 6) / 4 = 7.0. Mean of session means would be (10 + 6) / 2 = 8.0.
        val sessions = listOf(session("a", 10f), session("b", 6f, 6f, 6f))

        assertEquals(7f, averageRepScore(sessions), 0.0001f)
    }

    @Test
    fun `an empty session does not affect the pooled mean`() {
        val sessions = listOf(session("a", 9f, 7f), session("empty"))

        assertEquals(8f, averageRepScore(sessions), 0.0001f)
    }
}
