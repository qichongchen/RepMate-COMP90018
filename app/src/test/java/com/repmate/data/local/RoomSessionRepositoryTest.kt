package com.repmate.data.local

import com.example.repmate.data.auth.AuthRepository
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
        val fakeAuth = FakeAuthRepository("user-1")
        val repository = RoomSessionRepository(
            fakeDao,
            fakeAuth
        )

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
        val fakeAuth = FakeAuthRepository("user-1")
        val repository = RoomSessionRepository(
            fakeDao,
            fakeAuth
        )

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
        val fakeAuth = FakeAuthRepository("user-1")
        val repository = RoomSessionRepository(
            fakeDao,
            fakeAuth
        )

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

    @Test
    fun recentOnlyReturnsSessionsForCurrentUser() = runTest {
        val fakeDao = FakeSessionDao()
        val fakeAuth = FakeAuthRepository("user-1")
        val repository = RoomSessionRepository(
            fakeDao,
            fakeAuth
        )

        val user1Session = WorkoutSession(
            id = "user1-session",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = emptyList()
        )

        repository.save(user1Session)

        fakeAuth.setCurrentUserId("user-2")

        val user2Session = WorkoutSession(
            id = "user2-session",
            exercise = ExerciseType.PUSHUP,
            startedAt = 2000L,
            reps = emptyList()
        )

        repository.save(user2Session)

        val user2Result = repository.recent(10).first()

        assertEquals(1, user2Result.size)
        assertEquals("user2-session", user2Result[0].id)

        fakeAuth.setCurrentUserId("user-1")

        val user1Result = repository.recent(10).first()

        assertEquals(1, user1Result.size)
        assertEquals("user1-session", user1Result[0].id)
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
        ownerId: String,
        limit: Int
    ): Flow<List<WorkoutSessionEntity>> {
        return sessions.map { currentSessions ->
            currentSessions
                .filter { it.ownerId == ownerId }
                .sortedByDescending { it.startedAt }
                .take(limit)
        }
    }

    override suspend fun getSessionById(
        id: String,
        ownerId: String
    ): WorkoutSessionEntity? {
        return sessions.value.firstOrNull {
            it.id == id && it.ownerId == ownerId
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

private class FakeAuthRepository(
    private var userId: String? = "user-1"
) : AuthRepository {

    override suspend fun signInAnonymously(): Result<String> {
        val uid = userId
            ?: return Result.failure(
                IllegalStateException("No fake user")
            )

        return Result.success(uid)
    }

    override fun getCurrentUserId(): String? {
        return userId
    }

    fun setCurrentUserId(userId: String?) {
        this.userId = userId
    }
}