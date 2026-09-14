package com.repmate.data.local

import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomSessionRepositoryTest {

    @Test
    fun saveAndReadSession() = runTest {
        val fakeDao = FakeSessionDao()
        val repository = RoomSessionRepository(fakeDao)

        val session = WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = listOf(
                RepScore(
                    repIndex = 1,
                    score = 8.5f,
                    tempoSeconds = 2.0f,
                    rangePercent = 90,
                    pauseSeconds = 0.3f,
                    reasons = listOf("good depth")
                )
            )
        )

        repository.save(session)

        val result = repository.recent(10).first()

        assertEquals(1, result.size)
        assertEquals("session-1", result[0].id)
        assertEquals(ExerciseType.SQUAT, result[0].exercise)
        assertEquals(1, result[0].reps.size)
        assertEquals(8.5f, result[0].reps[0].score)
        assertEquals(listOf("good depth"), result[0].reps[0].reasons)
    }

    @Test
    fun recentReturnsSessionsNewestFirst() = runTest {
        val fakeDao = FakeSessionDao()
        val repository = RoomSessionRepository(fakeDao)

        val olderSession = WorkoutSession(
            id = "older",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = emptyList()
        )

        val newerSession = WorkoutSession(
            id = "newer",
            exercise = ExerciseType.PUSHUP,
            startedAt = 2000L,
            reps = emptyList()
        )

        repository.save(olderSession)
        repository.save(newerSession)

        val result = repository.recent(10).first()

        assertEquals(2, result.size)
        assertEquals("newer", result[0].id)
        assertEquals("older", result[1].id)
    }

    @Test
    fun saveWithSameIdReplacesExistingSession() = runTest {
        val fakeDao = FakeSessionDao()
        val repository = RoomSessionRepository(fakeDao)

        val originalSession = WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = listOf(
                RepScore(
                    repIndex = 1,
                    score = 7.0f,
                    tempoSeconds = 2.0f,
                    rangePercent = 80,
                    pauseSeconds = 0.2f,
                    reasons = listOf("original")
                )
            )
        )

        val updatedSession = WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = listOf(
                RepScore(
                    repIndex = 1,
                    score = 9.0f,
                    tempoSeconds = 1.8f,
                    rangePercent = 95,
                    pauseSeconds = 0.1f,
                    reasons = listOf("updated")
                )
            )
        )

        repository.save(originalSession)
        repository.save(updatedSession)

        val result = repository.recent(10).first()

        assertEquals(1, result.size)
        assertEquals(9.0f, result[0].reps[0].score)
        assertEquals(listOf("updated"), result[0].reps[0].reasons)
    }
}


private class FakeSessionDao : SessionDao {

    private val sessions =
        MutableStateFlow<List<WorkoutSessionEntity>>(emptyList())

    private val repScores =
        mutableMapOf<String, List<RepScoreEntity>>()

    override suspend fun insertSession(session: WorkoutSessionEntity) {
        sessions.value =
            sessions.value
                .filterNot { it.id == session.id } + session
    }

    override suspend fun insertRepScores(
        repScores: List<RepScoreEntity>
    ) {
        if (repScores.isNotEmpty()) {
            this.repScores[repScores.first().sessionId] = repScores
        }
    }

    override fun observeRecentSessions(
        limit: Int
    ): Flow<List<WorkoutSessionEntity>> {
        return sessions.map { currentSessions ->
            currentSessions
                .sortedByDescending { it.startedAt }
                .take(limit)
        }
    }

    override suspend fun getRepScores(
        sessionId: String
    ): List<RepScoreEntity> {
        return repScores[sessionId]
            .orEmpty()
            .sortedBy { it.repIndex }
    }

    override suspend fun deleteRepScores(sessionId: String) {
        repScores.remove(sessionId)
    }
}