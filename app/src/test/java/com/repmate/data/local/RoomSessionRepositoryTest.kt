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
            endedAt = 5000L,
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
        assertEquals(5000L, result[0].endedAt)
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
            endedAt = 3000L,
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
            endedAt = 5000L,
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


    @Test
    fun getMyBestScoreReturnsHighestAverage() = runTest {
        val fakeDao = FakeSessionDao()
        val repository = RoomSessionRepository(
            fakeDao,
            FakeAuthRepository("user-1")
        )

        suspend fun saveSession(id: String, score: Float) {
            repository.save(
                WorkoutSession(
                    id = id,
                    exercise = ExerciseType.SQUAT,
                    startedAt = 1000L,
                    reps = listOf(
                        RepScore(
                            repIndex = 1,
                            score = score,
                            tempoSeconds = 2.0f,
                            rangePercent = 90,
                            pauseSeconds = 0.3f,
                            reasons = emptyList()
                        )
                    )
                )
            )
        }

        saveSession("session-1", 7.0f)
        saveSession("session-2", 9.0f)
        saveSession("session-3", 8.0f)

        val result = repository.getMyBestScore(
            ExerciseType.SQUAT
        )

        assertEquals(9.0f, result?.score)
        assertEquals(1, result?.reps)
    }

    @Test
    fun getMyBestScoreReturnsNullWhenNoEligibleSession() = runTest {
        val repository = RoomSessionRepository(
            FakeSessionDao(),
            FakeAuthRepository("user-1")
        )

        assertEquals(
            null,
            repository.getMyBestScore(ExerciseType.SQUAT)
        )

        repository.save(
            WorkoutSession(
                id = "empty-session",
                exercise = ExerciseType.SQUAT,
                startedAt = 1000L,
                reps = emptyList()
            )
        )

        assertEquals(
            null,
            repository.getMyBestScore(ExerciseType.SQUAT)
        )
    }

    @Test
    fun getMyBestScoreOnlyReturnsCurrentUserScore() = runTest {
        val fakeDao = FakeSessionDao()
        val fakeAuth = FakeAuthRepository("user-1")
        val repository = RoomSessionRepository(
            fakeDao,
            fakeAuth
        )

        suspend fun saveSession(id: String, score: Float) {
            repository.save(
                WorkoutSession(
                    id = id,
                    exercise = ExerciseType.SQUAT,
                    startedAt = 1000L,
                    reps = listOf(
                        RepScore(
                            repIndex = 1,
                            score = score,
                            tempoSeconds = 2.0f,
                            rangePercent = 90,
                            pauseSeconds = 0.3f,
                            reasons = emptyList()
                        )
                    )
                )
            )
        }

        saveSession("user1-session", 7.0f)

        fakeAuth.setCurrentUserId("user-2")
        saveSession("user2-session", 9.0f)

        assertEquals(
            9.0f,
            repository.getMyBestScore(ExerciseType.SQUAT)?.score
        )

        fakeAuth.setCurrentUserId("user-1")

        assertEquals(
            7.0f,
            repository.getMyBestScore(ExerciseType.SQUAT)?.score
        )
    }

    // bestSessionFor: the local half of republishing Ghost Duel scores (see LocalBestScoreSync).

    private fun bestSessionRepo(userId: String? = "user-1") =
        FakeAuthRepository(userId).let { auth -> auth to RoomSessionRepository(FakeSessionDao(), auth) }

    private fun session(id: String, exercise: ExerciseType, startedAt: Long, vararg scores: Float) =
        WorkoutSession(
            id = id,
            exercise = exercise,
            startedAt = startedAt,
            endedAt = startedAt + 1000L,
            reps = scores.mapIndexed { i, score ->
                RepScore(
                    repIndex = i,
                    score = score,
                    tempoSeconds = 2f,
                    rangePercent = 90,
                    pauseSeconds = 0f,
                    reasons = emptyList()
                )
            }
        )

    @Test
    fun bestSessionForReturnsTheHighestAverageSessionWithItsReps() = runTest {
        val (_, repository) = bestSessionRepo()
        repository.save(session("low", ExerciseType.SQUAT, 1000L, 6f, 6f, 6f))
        repository.save(session("high", ExerciseType.SQUAT, 2000L, 9f, 8f))
        repository.save(session("other-exercise", ExerciseType.PUSHUP, 3000L, 10f))

        val best = repository.bestSessionFor(ExerciseType.SQUAT)

        assertEquals("high", best?.id)
        assertEquals(listOf(9f, 8f), best?.reps?.map { it.score })
    }

    @Test
    fun bestSessionForBreaksTiesByMoreRepsThenNewer() = runTest {
        val (_, repository) = bestSessionRepo()
        repository.save(session("few", ExerciseType.SQUAT, 3000L, 7f, 7f))
        repository.save(session("many-old", ExerciseType.SQUAT, 1000L, 7f, 7f, 7f))
        repository.save(session("many-new", ExerciseType.SQUAT, 2000L, 7f, 7f, 7f))

        assertEquals("many-new", repository.bestSessionFor(ExerciseType.SQUAT)?.id)
    }

    @Test
    fun bestSessionForIgnoresZeroRepSessionsAndReturnsNullWhenThereAreNone() = runTest {
        val (_, repository) = bestSessionRepo()
        repository.save(session("empty", ExerciseType.SQUAT, 1000L))

        assertEquals(null, repository.bestSessionFor(ExerciseType.SQUAT))
        assertEquals(null, repository.bestSessionFor(ExerciseType.JUMPING_JACK))

        repository.save(session("real", ExerciseType.SQUAT, 500L, 5f))
        assertEquals("real", repository.bestSessionFor(ExerciseType.SQUAT)?.id)
    }

    @Test
    fun bestSessionForNeverReturnsAnotherUsersSession() = runTest {
        val (auth, repository) = bestSessionRepo(userId = "someone-else")
        repository.save(session("theirs", ExerciseType.SQUAT, 1000L, 10f, 10f))

        auth.setCurrentUserId("user-1")
        repository.save(session("mine", ExerciseType.SQUAT, 2000L, 5f))

        // The other account's session scores higher but is not this user's.
        assertEquals("mine", repository.bestSessionFor(ExerciseType.SQUAT)?.id)
    }

    @Test
    fun bestSessionForReturnsNullWhenSignedOut() = runTest {
        val (auth, repository) = bestSessionRepo()
        repository.save(session("mine", ExerciseType.SQUAT, 1000L, 5f))

        auth.setCurrentUserId(null)

        assertEquals(null, repository.bestSessionFor(ExerciseType.SQUAT))
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

    override suspend fun getBestSessionScore(
        ownerId: String,
        exercise: String
    ): BestSessionResult? {
        return sessions.value
            .asSequence()
            .filter {
                it.ownerId == ownerId &&
                        it.exercise == exercise
            }
            .mapNotNull { session ->
                val reps = repScores[session.id].orEmpty()

                if (reps.isEmpty()) {
                    null
                } else {
                    val averageScore = reps
                        .map { it.score.toDouble() }
                        .average()

                    session to BestSessionResult(
                        averageScore = averageScore.toFloat(),
                        repCount = reps.size
                    )
                }
            }
            .sortedWith(
                compareByDescending<
                        Pair<WorkoutSessionEntity, BestSessionResult>
                        > { it.second.averageScore }
                    .thenByDescending { it.second.repCount }
                    .thenByDescending { it.first.startedAt }
            )
            .firstOrNull()
            ?.second
    }


    /** Same order as the real query: highest average, then more reps, then newer; 0-rep sessions never qualify. */
    override suspend fun getBestSessionId(
        ownerId: String,
        exercise: String
    ): String? {
        return sessions.value
            .filter { it.ownerId == ownerId && it.exercise == exercise }
            .filter { repScores[it.id].orEmpty().isNotEmpty() }
            .sortedWith(
                compareByDescending<WorkoutSessionEntity> {
                    repScores.getValue(it.id).map { rep -> rep.score.toDouble() }.average()
                }
                    .thenByDescending { repScores.getValue(it.id).size }
                    .thenByDescending { it.startedAt }
            )
            .firstOrNull()
            ?.id
    }

    override suspend fun getSessionsByOwner(
        ownerId: String
    ): List<WorkoutSessionEntity> {
        return sessions.value
            .filter { it.ownerId == ownerId }
            .sortedBy { it.startedAt }
    }

    override suspend fun reassignOwner(
        ids: List<String>,
        oldOwnerId: String,
        newOwnerId: String
    ): Int {
        var moved = 0
        sessions.value = sessions.value.map { session ->
            if (session.id in ids && session.ownerId == oldOwnerId) {
                moved++
                session.copy(ownerId = newOwnerId)
            } else {
                session
            }
        }
        return moved
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