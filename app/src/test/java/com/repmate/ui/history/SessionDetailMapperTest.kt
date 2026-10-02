package com.repmate.ui.history

import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import com.repmate.ui.components.hasFormScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun session(
    exercise: ExerciseType,
    endedAt: Long? = null,
) = WorkoutSession(
    id = "s1",
    exercise = exercise,
    startedAt = 1_000_000L,
    endedAt = endedAt,
    reps = listOf(RepScore(repIndex = 0, score = 10f, tempoSeconds = 2f, rangePercent = 100, pauseSeconds = 0f, reasons = emptyList())),
)

class SessionDetailMapperTest {

    @Test
    fun `history mode is the default and carries no safety note`() {
        val state = session(ExerciseType.SQUAT).toSessionDetailUiState()

        assertFalse(state.postWorkout)
        assertNull(state.safetyCheckInMinutes)
    }

    @Test
    fun `post-workout flag and safety minutes are carried through`() {
        val state = session(ExerciseType.SQUAT).toSessionDetailUiState(postWorkout = true, safetyCheckInMinutes = 5)

        assertTrue(state.postWorkout)
        assertEquals(5L, state.safetyCheckInMinutes)
    }

    @Test
    fun `post-workout without safety check-in has no note`() {
        val state = session(ExerciseType.SQUAT).toSessionDetailUiState(postWorkout = true, safetyCheckInMinutes = null)

        assertNull(state.safetyCheckInMinutes)
    }

    @Test
    fun `history mode never shows the safety note even if minutes are passed`() {
        val state = session(ExerciseType.SQUAT).toSessionDetailUiState(postWorkout = false, safetyCheckInMinutes = 5)

        assertNull(state.safetyCheckInMinutes)
    }

    @Test
    fun `push-up sessions are not form scored, in both modes`() {
        assertTrue(session(ExerciseType.PUSHUP).toSessionDetailUiState(postWorkout = false).hasFormScoring)
        assertTrue(session(ExerciseType.PUSHUP).toSessionDetailUiState(postWorkout = true).hasFormScoring)
    }

    @Test
    fun `squat and jumping jack sessions are form scored`() {
        assertTrue(session(ExerciseType.SQUAT).toSessionDetailUiState().hasFormScoring)
        assertTrue(session(ExerciseType.JUMPING_JACK).toSessionDetailUiState().hasFormScoring)
    }

    @Test
    fun `a push-up session with an end time has a duration`() {
        val state = session(ExerciseType.PUSHUP, endedAt = 1_000_000L + 65_000L).toSessionDetailUiState(postWorkout = true)

        assertEquals("1:05", state.durationLabel)
    }

    @Test
    fun `hasFormScoring is true for every exercise`() {
        assertEquals(emptySet<ExerciseType>(), ExerciseType.entries.filterNot { it.hasFormScoring }.toSet())
    }
}
