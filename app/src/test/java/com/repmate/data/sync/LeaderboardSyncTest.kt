package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LeaderboardSync] is the producer the `leaderboard` collection never had, so these tests cover
 * what it writes and -- just as important -- when it writes nothing at all.
 */
class LeaderboardSyncTest {
    private val auth = LeaderboardFakeAuth(uid = ACCOUNT, anonymous = false)
    private val local = FakeLocalBestSessions()
    private val writer = RecordingLeaderboardWriter()
    private val sync = LeaderboardSync(auth, local, writer)

    @Test
    fun publishesTheSummedPointsOfEveryExerciseBest() =
        runTest {
            local.best[ExerciseType.SQUAT] = session(scores = List(10) { 8f })
            local.best[ExerciseType.JUMPING_JACK] = session(scores = List(12) { 7.5f })

            assertNull(sync.publish())

            // 8.0 x 10 + 7.5 x 12 = 170.
            assertEquals(listOf(170), writer.written)
        }

    @Test
    fun writesNothingWhenNobodyIsSignedIn() =
        runTest {
            auth.uid = null
            local.best[ExerciseType.SQUAT] = session(scores = List(10) { 8f })

            assertNull(sync.publish())

            assertEquals(emptyList<Int>(), writer.written)
        }

    @Test
    fun writesNothingForAGuest() =
        runTest {
            // A guest's row would be keyed by a UID that stops existing the moment they sign in.
            auth.anonymous = true
            local.best[ExerciseType.SQUAT] = session(scores = List(10) { 8f })

            assertNull(sync.publish())

            assertEquals(emptyList<Int>(), writer.written)
        }

    @Test
    fun writesNothingWhenNoSessionHasBeenScoredYet() =
        runTest {
            // Writing a 0 would list the user below everyone who has actually worked out.
            assertNull(sync.publish())

            assertEquals(emptyList<Int>(), writer.written)
        }

    @Test
    fun aSessionWithNoRepsDoesNotCount() =
        runTest {
            local.best[ExerciseType.SQUAT] = session(scores = emptyList())

            assertNull(sync.publish())

            assertEquals(emptyList<Int>(), writer.written)
        }

    @Test
    fun writesNothingWhenTheSignedInUserChangesWhileReadingRoom() =
        runTest {
            local.best[ExerciseType.SQUAT] = session(scores = List(10) { 8f })
            // Someone else is signed in by the time the points are ready: these are not their
            // points, and the writer targets whoever is signed in now.
            local.onRead = { auth.uid = "someone-else" }

            assertNull(sync.publish())

            assertEquals(emptyList<Int>(), writer.written)
        }

    @Test
    fun aFailedWriteIsReturnedRatherThanThrown() =
        runTest {
            local.best[ExerciseType.SQUAT] = session(scores = List(10) { 8f })
            writer.failWith = RuntimeException("PERMISSION_DENIED")

            val error = sync.publish()

            // A leaderboard row is the least important part of saving a workout: the caller logs
            // this and carries on.
            assertNotNull(error)
            assertEquals("PERMISSION_DENIED", error?.message)
        }

    @Test
    fun aThrowingRoomReadIsReturnedRatherThanThrown() =
        runTest {
            local.onRead = { throw IllegalStateException("database closed") }

            val error = sync.publish()

            assertNotNull(error)
            assertEquals("database closed", error?.message)
        }

    @Test
    fun publishingTwiceWithTheSameHistoryWritesTheSameNumber() =
        runTest {
            local.best[ExerciseType.SQUAT] = session(scores = List(10) { 8f })

            sync.publish()
            sync.publish()

            // Idempotent by construction: it is recomputed from local history, never incremented.
            assertEquals(listOf(80, 80), writer.written)
        }

    private companion object {
        const val ACCOUNT = "account-uid"

        fun session(scores: List<Float>) =
            WorkoutSession(
                id = "s1",
                exercise = ExerciseType.SQUAT,
                startedAt = 1_000L,
                endedAt = 2_000L,
                reps =
                    scores.mapIndexed { index, score ->
                        RepScore(
                            repIndex = index + 1,
                            score = score,
                            tempoSeconds = 1.5f,
                            rangePercent = 90,
                            pauseSeconds = 0f,
                            reasons = emptyList(),
                        )
                    },
            )
    }
}

private class LeaderboardFakeAuth(
    var uid: String?,
    var anonymous: Boolean,
) : AuthRepository {
    override suspend fun signInAnonymously(): Result<String> = Result.failure(UnsupportedOperationException())

    override fun getCurrentUserId(): String? = uid

    override fun isCurrentUserAnonymous(): Boolean = anonymous
}

private class FakeLocalBestSessions : LocalBestSessionSource {
    val best = mutableMapOf<ExerciseType, WorkoutSession>()

    /** Runs on each read, so a test can change the signed-in user or throw mid-way. */
    var onRead: (() -> Unit)? = null

    override suspend fun bestSessionFor(exercise: ExerciseType): WorkoutSession? {
        onRead?.invoke()
        return best[exercise]
    }
}

private class RecordingLeaderboardWriter : LeaderboardWriter {
    val written = mutableListOf<Int>()
    var failWith: Throwable? = null

    override suspend fun write(points: Int): Result<Unit> {
        failWith?.let { return Result.failure(it) }
        written += points
        return Result.success(Unit)
    }
}
