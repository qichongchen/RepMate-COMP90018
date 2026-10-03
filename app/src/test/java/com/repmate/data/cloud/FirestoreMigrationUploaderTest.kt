package com.repmate.data.cloud

import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FirestoreMigrationUploader]'s one job beyond delegating: it must refuse to write a guest's
 * sessions unless the signed-in user *is* the account the merge is for.
 *
 * That check is what keeps the merge inside the Firestore rules. The underlying write always goes
 * to `users/{signed-in uid}/workoutSessions`, so a stale or mismatched target must stop the write
 * rather than quietly send one user's history into whoever happens to be signed in now. These
 * tests therefore assert not just the failed [Result] but that **nothing was written at all**.
 */
class FirestoreMigrationUploaderTest {
    private val auth = FakeAuthRepository()
    private val cloud = RecordingWorkoutUploader()
    private val uploader = FirestoreMigrationUploader(auth, cloud)

    private val session =
        WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1_000L,
            endedAt = 2_000L,
            reps = emptyList(),
        )

    @Test
    fun uploadsWhenTheSignedInUserIsTheTarget() =
        runTest {
            auth.uid = "account-uid"

            assertTrue(uploader.upload(session, targetUid = "account-uid").isSuccess)

            assertEquals(listOf("session-1"), cloud.uploaded)
        }

    @Test
    fun refusesAndWritesNothingWhenAnotherAccountIsSignedIn() =
        runTest {
            auth.uid = "someone-else-uid"

            val result = uploader.upload(session, targetUid = "account-uid")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            // The important half: no document was written under the signed-in user either.
            assertTrue(cloud.uploaded.isEmpty())
        }

    @Test
    fun refusesAndWritesNothingWhenNobodyIsSignedIn() =
        runTest {
            auth.uid = null

            val result = uploader.upload(session, targetUid = "account-uid")

            assertTrue(result.isFailure)
            assertTrue(cloud.uploaded.isEmpty())
        }

    @Test
    fun refusesWhenTheTargetIsTheGuestThatIsStillSignedIn() =
        runTest {
            // The guest was never replaced, so a merge into their own UID is not a merge at all;
            // the migrator drops such an entry, and the uploader would not be reached. Belt and
            // braces: even reached directly, a target that is not the signed-in user is refused.
            auth.uid = "guest-uid"

            assertTrue(uploader.upload(session, targetUid = "account-uid").isFailure)
            assertTrue(cloud.uploaded.isEmpty())
        }

    @Test
    fun passesAFailedWriteThrough() =
        runTest {
            auth.uid = "account-uid"
            cloud.failWith = RuntimeException("offline")

            val result = uploader.upload(session, targetUid = "account-uid")

            assertTrue(result.isFailure)
            assertEquals("offline", result.exceptionOrNull()?.message)
            // Attempted, unlike the refusals above: the guard passed and the write itself failed.
            assertFalse(cloud.attempts == 0)
        }
}

private class FakeAuthRepository(
    var uid: String? = null,
) : AuthRepository {
    override suspend fun signInAnonymously(): Result<String> = Result.failure(UnsupportedOperationException())

    override fun getCurrentUserId(): String? = uid
}

private class RecordingWorkoutUploader : WorkoutUploader {
    val uploaded = mutableListOf<String>()
    var attempts = 0
    var failWith: Throwable? = null

    override suspend fun upload(session: WorkoutSession): Result<Unit> {
        attempts++
        failWith?.let { return Result.failure(it) }
        uploaded += session.id
        return Result.success(Unit)
    }
}
