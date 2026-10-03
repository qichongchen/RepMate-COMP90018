package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GuestHistoryMigrator] against in-memory fakes. The Room `UPDATE` and the DataStore marker are
 * not exercised here (they need a device); see the manual check in the change summary.
 */
class GuestHistoryMigratorTest {
    private val auth = FakeAuth(uid = GUEST)
    private val marker = FakeMarkerStore()
    private val local = FakeGuestSessionStore()
    private val cloud = FakeUploader(auth)
    private val migrator = GuestHistoryMigrator(marker, local, cloud, auth)

    private fun seedGuestSessions() {
        local.add(ownerId = GUEST, session = session("a", reps = 3))
        local.add(ownerId = GUEST, session = session("b", reps = 5, exercise = ExerciseType.PUSHUP))
    }

    /** Guest captured, then the sign-in replaces the guest with [ACCOUNT]. */
    private suspend fun captureAndSignIn() {
        migrator.markPending(GUEST)
        auth.uid = ACCOUNT
        migrator.recordSignIn()
    }

    @Test
    fun runningTwiceCreatesNoDuplicates() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()

            assertEquals(2, migrator.resume().getOrThrow())
            // The marker is gone, so a second run is a no-op, and even a forced repeat of the
            // underlying steps cannot double anything: uploads are keyed by id, re-owning matches
            // nothing once moved.
            assertEquals(0, migrator.resume().getOrThrow())
            marker.save(PendingGuestMigration(GUEST, ACCOUNT))
            assertEquals(0, migrator.resume().getOrThrow())

            assertEquals(setOf("a", "b"), local.idsOwnedBy(ACCOUNT))
            assertEquals(emptySet<String>(), local.idsOwnedBy(GUEST))
            assertEquals(setOf("a", "b"), cloud.documentIds(ACCOUNT))
            assertEquals(2, cloud.documentCount(ACCOUNT))
            assertNull(marker.get())
        }

    @Test
    fun failedUploadLeavesEverythingWithTheGuestAndKeepsTheMarker() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()
            cloud.failOnSessionId = "b"

            assertTrue(migrator.resume().isFailure)

            // "a" uploaded fine, but nothing is re-owned until every upload has succeeded.
            assertEquals(setOf("a", "b"), local.idsOwnedBy(GUEST))
            assertEquals(emptySet<String>(), local.idsOwnedBy(ACCOUNT))
            assertEquals(PendingGuestMigration(GUEST, ACCOUNT), marker.get())
        }

    @Test
    fun retryAfterAFailureCompletesTheMerge() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()
            cloud.failOnSessionId = "b"
            assertTrue(migrator.resume().isFailure)

            cloud.failOnSessionId = null
            assertEquals(2, migrator.resume().getOrThrow())

            assertEquals(setOf("a", "b"), local.idsOwnedBy(ACCOUNT))
            assertEquals(setOf("a", "b"), cloud.documentIds(ACCOUNT))
            // "a" was uploaded on both attempts; the upsert kept it to one document.
            assertEquals(2, cloud.documentCount(ACCOUNT))
            assertNull(marker.get())
        }

    @Test
    fun matchingCurrentAndGuestUidDoesNothingAndClearsTheMarker() =
        runTest {
            seedGuestSessions()
            migrator.markPending(GUEST)
            // No account switch: still the same user after the "sign-in".
            migrator.recordSignIn()

            assertNull(marker.get())
            assertEquals(0, migrator.resume().getOrThrow())
            assertEquals(setOf("a", "b"), local.idsOwnedBy(GUEST))
            assertEquals(0, cloud.uploadAttempts)
        }

    @Test
    fun matchingUidOnResumeAfterAKillClearsTheMarker() =
        runTest {
            seedGuestSessions()
            // Captured, then the app died before the sign-in went through: still the guest.
            migrator.markPending(GUEST)

            assertEquals(0, migrator.resume().getOrThrow())
            assertNull(marker.get())
            assertEquals(0, cloud.uploadAttempts)
        }

    @Test
    fun zeroRepSessionsAreMergedToo() =
        runTest {
            local.add(GUEST, session("empty", reps = 0))
            local.add(GUEST, session("full", reps = 2))
            captureAndSignIn()

            assertEquals(2, migrator.resume().getOrThrow())

            assertEquals(setOf("empty", "full"), local.idsOwnedBy(ACCOUNT))
            assertEquals(emptySet<String>(), local.idsOwnedBy(GUEST))
            assertEquals(setOf("empty", "full"), cloud.documentIds(ACCOUNT))
        }

    @Test
    fun onlyTheCapturedGuestsSessionsAreMigrated() =
        runTest {
            local.add(GUEST, session("mine", reps = 2))
            local.add("someone-else", session("theirs", reps = 2))
            local.add(ACCOUNT, session("existing", reps = 2))
            captureAndSignIn()

            migrator.resume().getOrThrow()

            assertEquals(setOf("mine", "existing"), local.idsOwnedBy(ACCOUNT))
            assertEquals(setOf("theirs"), local.idsOwnedBy("someone-else"))
            assertEquals(setOf("mine"), cloud.documentIds(ACCOUNT))
        }

    @Test
    fun aDifferentAccountSignedInLeavesTheMarkerAndMovesNothing() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()
            // The user later ends up in another account before the merge could finish.
            auth.uid = "other-account"

            assertEquals(0, migrator.resume().getOrThrow())

            assertEquals(setOf("a", "b"), local.idsOwnedBy(GUEST))
            assertEquals(PendingGuestMigration(GUEST, ACCOUNT), marker.get())
            assertEquals(0, cloud.uploadAttempts)
        }

    @Test
    fun resumeAdoptsTheSignedInAccountWhenTheTargetWasNeverRecorded() =
        runTest {
            seedGuestSessions()
            migrator.markPending(GUEST)
            // Sign-in succeeded, but the app died before recordSignIn ran.
            auth.uid = ACCOUNT

            assertEquals(2, migrator.resume().getOrThrow())
            assertEquals(setOf("a", "b"), local.idsOwnedBy(ACCOUNT))
            assertNull(marker.get())
        }

    @Test
    fun aFailedOrCancelledSignInDiscardsTheCapture() =
        runTest {
            migrator.markPending(GUEST)
            assertNotNull(marker.get())

            migrator.discardUnresolvedCapture(GUEST)

            assertNull(marker.get())
        }

    @Test
    fun discardDoesNotTouchAMarkerOnceTheSignInHasSucceeded() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()

            migrator.discardUnresolvedCapture(GUEST)

            assertEquals(PendingGuestMigration(GUEST, ACCOUNT), marker.get())
        }

    @Test
    fun signedOutLeavesTheMarkerInPlace() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()
            auth.uid = null

            assertEquals(0, migrator.resume().getOrThrow())
            assertEquals(PendingGuestMigration(GUEST, ACCOUNT), marker.get())
        }

    // ---------------------------------------------------------------------------------------
    // More than one pending merge. The store used to hold a single marker, so the sequence in
    // bothGuestHistoriesSurviveTwoOfflineLogins lost the first guest's rows for good.
    // ---------------------------------------------------------------------------------------

    @Test
    fun aSecondGuestCaptureDoesNotOverwriteTheFirst() =
        runTest {
            migrator.markPending(GUEST)
            migrator.markPending(SECOND_GUEST)

            assertEquals(
                listOf(PendingGuestMigration(GUEST), PendingGuestMigration(SECOND_GUEST)),
                marker.all(),
            )
        }

    @Test
    fun bothGuestHistoriesSurviveTwoOfflineLogins() =
        runTest {
            // First guest: two sessions, logs in, and the merge cannot reach Firestore.
            seedGuestSessions()
            cloud.failOnSessionId = "a"
            captureAndSignIn()
            assertTrue(migrator.resume().isFailure)

            // Signs out, uses the app as a guest again (a brand-new anonymous UID), works out,
            // and logs in to the same account -- this is the capture that used to overwrite.
            auth.uid = SECOND_GUEST
            local.add(ownerId = SECOND_GUEST, session = session("c", reps = 4))
            migrator.markPending(SECOND_GUEST)
            auth.uid = ACCOUNT
            migrator.recordSignIn()

            // Back online: both guests' histories land in the account.
            cloud.failOnSessionId = null
            assertEquals(3, migrator.resume().getOrThrow())

            assertEquals(setOf("a", "b", "c"), local.idsOwnedBy(ACCOUNT))
            assertEquals(emptySet<String>(), local.idsOwnedBy(GUEST))
            assertEquals(emptySet<String>(), local.idsOwnedBy(SECOND_GUEST))
            assertEquals(setOf("a", "b", "c"), cloud.documentIds(ACCOUNT))
            assertEquals(emptyList<PendingGuestMigration>(), marker.all())
        }

    @Test
    fun anEntryThatKeepsFailingDoesNotStrandTheOthers() =
        runTest {
            local.add(ownerId = GUEST, session = session("a", reps = 3))
            local.add(ownerId = SECOND_GUEST, session = session("c", reps = 4))
            // "a" is the one Firestore will not take, and it is first in the queue.
            cloud.failOnSessionId = "a"
            migrator.markPending(GUEST)
            migrator.markPending(SECOND_GUEST)
            auth.uid = ACCOUNT
            migrator.recordSignIn()

            val result = migrator.resume()

            assertTrue(result.isFailure)
            // The healthy entry still went through, and only the broken one is left to retry.
            assertEquals(setOf("c"), local.idsOwnedBy(ACCOUNT))
            assertEquals(setOf("a"), local.idsOwnedBy(GUEST))
            assertEquals(listOf(PendingGuestMigration(GUEST, ACCOUNT)), marker.all())
        }

    @Test
    fun discardingOneFailedSignInLeavesAnEarlierCaptureAlone() =
        runTest {
            // An earlier capture the app never resolved (killed before recordSignIn).
            migrator.markPending(GUEST)
            // A second guest session whose sign-in then fails.
            migrator.markPending(SECOND_GUEST)

            migrator.discardUnresolvedCapture(SECOND_GUEST)

            assertEquals(listOf(PendingGuestMigration(GUEST)), marker.all())
        }

    @Test
    fun markPendingDoesNotWipeATargetAlreadyRecorded() =
        runTest {
            seedGuestSessions()
            captureAndSignIn()

            // A repeat capture for the same guest must not reset the target to null, which would
            // let discardUnresolvedCapture throw the entry away after a successful sign-in.
            migrator.markPending(GUEST)

            assertEquals(listOf(PendingGuestMigration(GUEST, ACCOUNT)), marker.all())
        }

    private companion object {
        const val GUEST = "guest-uid"
        const val SECOND_GUEST = "second-guest-uid"
        const val ACCOUNT = "account-uid"

        fun session(
            id: String,
            reps: Int,
            exercise: ExerciseType = ExerciseType.SQUAT,
        ) = WorkoutSession(
            id = id,
            exercise = exercise,
            startedAt = 1_000L,
            endedAt = 2_000L,
            reps =
                List(reps) { index ->
                    RepScore(
                        repIndex = index + 1,
                        score = 7.5f,
                        tempoSeconds = 1.5f,
                        rangePercent = 90,
                        pauseSeconds = 0f,
                        reasons = listOf("good depth"),
                    )
                },
        )
    }
}

private class FakeAuth(
    var uid: String?,
) : AuthRepository {
    override suspend fun signInAnonymously(): Result<String> = Result.failure(UnsupportedOperationException())

    override fun getCurrentUserId(): String? = uid
}

private class FakeMarkerStore : PendingMigrationStore {
    private val stored = mutableListOf<PendingGuestMigration>()

    /** The first pending entry, for the tests that only ever create one. */
    fun get(): PendingGuestMigration? = stored.firstOrNull()

    val size: Int get() = stored.size

    override suspend fun all(): List<PendingGuestMigration> = stored.toList()

    override suspend fun save(migration: PendingGuestMigration) {
        val at = stored.indexOfFirst { it.guestUid == migration.guestUid }
        // Upsert in place, like the DataStore store: recording a target must not reorder the queue.
        if (at >= 0) stored[at] = migration else stored += migration
    }

    override suspend fun remove(guestUid: String) {
        stored.removeAll { it.guestUid == guestUid }
    }
}

/** In-memory stand-in for Room: one row per session id, like the real table's primary key. */
private class FakeGuestSessionStore : GuestSessionStore {
    private data class Row(
        var ownerId: String,
        val session: WorkoutSession,
    )

    private val rows = mutableMapOf<String, Row>()

    fun add(
        ownerId: String,
        session: WorkoutSession,
    ) {
        rows[session.id] = Row(ownerId, session)
    }

    fun idsOwnedBy(ownerId: String): Set<String> = rows.values.filter { it.ownerId == ownerId }.map { it.session.id }.toSet()

    override suspend fun sessionsOwnedBy(ownerId: String) = rows.values.filter { it.ownerId == ownerId }.map { it.session }

    override suspend fun reassignOwner(
        sessionIds: List<String>,
        fromOwnerId: String,
        toOwnerId: String,
    ) {
        rows.values.filter { it.session.id in sessionIds && it.ownerId == fromOwnerId }.forEach { it.ownerId = toOwnerId }
    }
}

/** In-memory Firestore: documents keyed by (uid, session id), so a repeat upload overwrites like `set`. */
private class FakeUploader(
    private val auth: FakeAuth,
) : MigrationUploader {
    var failOnSessionId: String? = null
    var uploadAttempts = 0
    private val documents = mutableMapOf<Pair<String, String>, WorkoutSession>()

    fun documentIds(uid: String): Set<String> = documents.keys.filter { it.first == uid }.map { it.second }.toSet()

    fun documentCount(uid: String): Int = documents.keys.count { it.first == uid }

    override suspend fun upload(
        session: WorkoutSession,
        targetUid: String,
    ): Result<Unit> {
        uploadAttempts++
        if (auth.uid != targetUid) return Result.failure(IllegalStateException("not the signed-in user"))
        if (session.id == failOnSessionId) return Result.failure(RuntimeException("offline"))
        documents[targetUid to session.id] = session
        return Result.success(Unit)
    }
}
