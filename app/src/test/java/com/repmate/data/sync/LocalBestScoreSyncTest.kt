package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalBestScoreSync] against in-memory fakes. `SyncingSessionRepository`, which calls it after
 * a save and after a restore, cannot run in a JVM test (concrete Firestore classes, `android.util.Log`),
 * so everything it decides is kept in this class and covered here.
 */
class LocalBestScoreSyncTest {
    private val auth = SyncFakeAuth(uid = USER)
    private val local = FakeLocalSessions()
    private val publisher = FakeBestScorePublisher()
    private val sync = LocalBestScoreSync(auth, local, publisher)

    @Test
    fun publishesTheBestLocalSessionOfEachExerciseOnce() =
        runTest {
            local.best[ExerciseType.SQUAT] = session("squat-best", ExerciseType.SQUAT, reps = 4)
            local.best[ExerciseType.PUSHUP] = session("pushup-best", ExerciseType.PUSHUP, reps = 2)
            local.best[ExerciseType.JUMPING_JACK] = session("jj-best", ExerciseType.JUMPING_JACK, reps = 6)

            val failures = sync.publishAll()

            assertTrue(failures.isEmpty())
            assertEquals(listOf("squat-best", "pushup-best", "jj-best"), publisher.publishedIds())
        }

    @Test
    fun skipsExercisesWithNoSessionsAndOnesWithOnlyZeroRepSessions() =
        runTest {
            local.best[ExerciseType.SQUAT] = session("squat-empty", ExerciseType.SQUAT, reps = 0)
            local.best[ExerciseType.PUSHUP] = null
            local.best[ExerciseType.JUMPING_JACK] = session("jj", ExerciseType.JUMPING_JACK, reps = 3)

            assertTrue(sync.publishAll().isEmpty())

            assertEquals(listOf("jj"), publisher.publishedIds())
        }

    @Test
    fun publishesNothingWhenThereAreNoSessionsAtAll() =
        runTest {
            assertTrue(sync.publishAll().isEmpty())

            assertTrue(publisher.calls.isEmpty())
        }

    @Test
    fun doesNotPublishForAGuest() =
        runTest {
            auth.anonymous = true
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 5)

            assertTrue(sync.publishAll().isEmpty())

            assertTrue(publisher.calls.isEmpty())
            assertEquals(0, local.reads)
        }

    @Test
    fun doesNothingWhenNobodyIsSignedIn() =
        runTest {
            auth.uid = null
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 5)

            assertTrue(sync.publishAll().isEmpty())

            assertTrue(publisher.calls.isEmpty())
        }

    @Test
    fun aFailedPublishIsReportedAndTheOtherExercisesAreStillAttempted() =
        runTest {
            val offline = RuntimeException("offline")
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 3)
            local.best[ExerciseType.PUSHUP] = session("pushup", ExerciseType.PUSHUP, reps = 3)
            local.best[ExerciseType.JUMPING_JACK] = session("jj", ExerciseType.JUMPING_JACK, reps = 3)
            publisher.resultFor["squat"] = Result.failure(offline)

            val failures = sync.publishAll()

            assertEquals(setOf(ExerciseType.SQUAT), failures.keys)
            assertSame(offline, failures[ExerciseType.SQUAT])
            assertEquals(listOf("squat", "pushup", "jj"), publisher.publishedIds())
        }

    @Test
    fun aThrowingPublisherIsReportedAndTheOtherExercisesAreStillAttempted() =
        runTest {
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 3)
            local.best[ExerciseType.PUSHUP] = session("pushup", ExerciseType.PUSHUP, reps = 3)
            publisher.throwFor["squat"] = IllegalStateException("boom")

            val failures = sync.publishAll()

            assertEquals(setOf(ExerciseType.SQUAT), failures.keys)
            assertEquals(listOf("squat", "pushup"), publisher.publishedIds())
        }

    @Test
    fun aFailureReadingLocalSessionsIsReportedAndTheOtherExercisesAreStillAttempted() =
        runTest {
            local.best[ExerciseType.PUSHUP] = session("pushup", ExerciseType.PUSHUP, reps = 3)
            local.throwFor += ExerciseType.SQUAT

            val failures = sync.publishAll()

            assertEquals(setOf(ExerciseType.SQUAT), failures.keys)
            assertEquals(listOf("pushup"), publisher.publishedIds())
        }

    @Test
    fun aFailureInEveryExerciseStillReturnsNormally() =
        runTest {
            ExerciseType.entries.forEach { local.best[it] = session("s-$it", it, reps = 2) }
            publisher.defaultResult = Result.failure(RuntimeException("offline"))

            val failures = sync.publishAll()

            assertEquals(ExerciseType.entries.toSet(), failures.keys)
            assertEquals(3, publisher.calls.size)
        }

    @Test
    fun cancellationIsNotSwallowed() =
        runTest {
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 3)
            publisher.throwFor["squat"] = CancellationException("cancelled")

            val thrown = runCatching { sync.publishAll() }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
        }

    @Test
    fun stopsWithoutPublishingIfTheSignedInUserChangesAfterReadingTheSessions() =
        runTest {
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 3)
            local.best[ExerciseType.PUSHUP] = session("pushup", ExerciseType.PUSHUP, reps = 3)
            // Another account becomes the signed-in one while the first exercise is being read.
            local.afterRead = { auth.uid = "other-account" }

            assertTrue(sync.publishAll().isEmpty())

            assertTrue(publisher.calls.isEmpty())
        }

    @Test
    fun repeatingTheCallOffersTheSameSessionsAgain() =
        runTest {
            local.best[ExerciseType.SQUAT] = session("squat", ExerciseType.SQUAT, reps = 3)

            sync.publishAll()
            sync.publishAll()

            // Safe because the store only overwrites with a better score; the caller just asks again.
            assertEquals(listOf("squat", "squat"), publisher.publishedIds())
        }

    private companion object {
        const val USER = "user-uid"

        fun session(
            id: String,
            exercise: ExerciseType,
            reps: Int,
        ) = WorkoutSession(
            id = id,
            exercise = exercise,
            startedAt = 1_000L,
            endedAt = 2_000L,
            reps =
                List(reps) { index ->
                    RepScore(
                        repIndex = index,
                        score = 7.5f,
                        tempoSeconds = 1.5f,
                        rangePercent = 90,
                        pauseSeconds = 0f,
                        reasons = emptyList(),
                    )
                },
        )
    }
}

private class SyncFakeAuth(
    var uid: String?,
    var anonymous: Boolean = false,
) : AuthRepository {
    override suspend fun signInAnonymously(): Result<String> = Result.failure(UnsupportedOperationException())

    override fun getCurrentUserId(): String? = uid

    override fun isCurrentUserAnonymous(): Boolean = anonymous
}

/** Returns a preset "best" session per exercise, like the Room query would, and can fail on demand. */
private class FakeLocalSessions : LocalBestSessionSource {
    val best = mutableMapOf<ExerciseType, WorkoutSession?>()
    val throwFor = mutableSetOf<ExerciseType>()
    var reads = 0
    var afterRead: () -> Unit = {}

    override suspend fun bestSessionFor(exercise: ExerciseType): WorkoutSession? {
        reads++
        if (exercise in throwFor) throw IllegalStateException("disk error")
        return best[exercise].also { afterRead() }
    }
}

/**
 * Records every publish call. [resultFor] / [throwFor] are keyed by session id; the call is
 * recorded first either way, so a test can tell that it was attempted.
 */
private class FakeBestScorePublisher : BestScorePublisher {
    val calls = mutableListOf<WorkoutSession>()
    val resultFor = mutableMapOf<String, Result<Unit>>()
    val throwFor = mutableMapOf<String, Throwable>()
    var defaultResult: Result<Unit> = Result.success(Unit)

    fun publishedIds(): List<String> = calls.map { it.id }

    override suspend fun publishBestScore(session: WorkoutSession): Result<Unit> {
        calls += session
        throwFor[session.id]?.let { throw it }
        return resultFor[session.id] ?: defaultResult
    }
}
