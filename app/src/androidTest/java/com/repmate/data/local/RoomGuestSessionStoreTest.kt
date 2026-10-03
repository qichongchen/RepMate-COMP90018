package com.repmate.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.data.RepMateDatabase
import com.repmate.engine.ExerciseType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RoomGuestSessionStore] against a real database, because the guarantees the guest merge rests on
 * are properties of the SQL, not of the Kotlin around it:
 *
 * - `reassignOwner` is keyed on the **old owner** as well as the id, which is what makes a repeat
 *   call a no-op and what stops it touching a row that was never the guest's;
 * - the rep rows reference the session id, which the move does not change, so scores must survive;
 * - the ids are chunked, so a history longer than SQLite's bound-variable limit still moves.
 *
 * A fake DAO can satisfy the Kotlin and prove none of that, which is why this is instrumented.
 */
@RunWith(AndroidJUnit4::class)
class RoomGuestSessionStoreTest {
    private lateinit var database: RepMateDatabase
    private lateinit var dao: SessionDao
    private lateinit var store: RoomGuestSessionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    RepMateDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.sessionDao()
        store = RoomGuestSessionStore(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reassignOwnerMovesTheGuestsSessions() =
        runTest {
            insert(id = "guest-a", owner = GUEST)
            insert(id = "guest-b", owner = GUEST)
            insert(id = "already-theirs", owner = ACCOUNT)

            store.reassignOwner(listOf("guest-a", "guest-b"), fromOwnerId = GUEST, toOwnerId = ACCOUNT)

            assertEquals(emptyList<String>(), idsOwnedBy(GUEST))
            assertEquals(listOf("already-theirs", "guest-a", "guest-b"), idsOwnedBy(ACCOUNT).sorted())
        }

    @Test
    fun reassignOwnerLeavesAnotherAccountsSessionAlone() =
        runTest {
            insert(id = "guest-a", owner = GUEST)
            insert(id = "someone-elses", owner = OTHER_ACCOUNT)

            // The id is named, but the row is not the guest's, so the old-owner clause must save it.
            store.reassignOwner(listOf("guest-a", "someone-elses"), fromOwnerId = GUEST, toOwnerId = ACCOUNT)

            assertEquals(listOf("someone-elses"), idsOwnedBy(OTHER_ACCOUNT))
            assertEquals(listOf("guest-a"), idsOwnedBy(ACCOUNT))
        }

    @Test
    fun repeatingTheReassignChangesNothing() =
        runTest {
            insert(id = "guest-a", owner = GUEST)

            store.reassignOwner(listOf("guest-a"), fromOwnerId = GUEST, toOwnerId = ACCOUNT)
            // What a retry after a crash does: it must not move the row back, double it, or fail.
            store.reassignOwner(listOf("guest-a"), fromOwnerId = GUEST, toOwnerId = ACCOUNT)

            assertEquals(listOf("guest-a"), idsOwnedBy(ACCOUNT))
            assertEquals(emptyList<String>(), idsOwnedBy(GUEST))
            assertEquals(1, dao.getSessionsByOwner(ACCOUNT).size)
        }

    @Test
    fun theDaoReportsHowManyRowsItActuallyMoved() =
        runTest {
            insert(id = "guest-a", owner = GUEST)
            insert(id = "someone-elses", owner = OTHER_ACCOUNT)

            val moved = dao.reassignOwner(listOf("guest-a", "someone-elses", "no-such-id"), GUEST, ACCOUNT)
            val movedAgain = dao.reassignOwner(listOf("guest-a"), GUEST, ACCOUNT)

            assertEquals(1, moved)
            // 0 on the retry states the idempotency as a number rather than leaving it inferred.
            assertEquals(0, movedAgain)
        }

    @Test
    fun theRepScoresFollowTheSessionToItsNewOwner() =
        runTest {
            insert(id = "guest-a", owner = GUEST, reps = 3)

            store.reassignOwner(listOf("guest-a"), fromOwnerId = GUEST, toOwnerId = ACCOUNT)

            val merged = store.sessionsOwnedBy(ACCOUNT).single()
            assertEquals("guest-a", merged.id)
            assertEquals(3, merged.reps.size)
            assertEquals(listOf("good depth"), merged.reps.first().reasons)
        }

    @Test
    fun aHistoryLongerThanOneChunkMovesWholly() =
        runTest {
            // Past the 500-id chunk size, so more than one UPDATE runs. SQLite's own limit on
            // bound variables is what that chunking is there for.
            val ids = (1..1_200).map { "guest-session-$it" }
            ids.forEach { insert(id = it, owner = GUEST) }

            store.reassignOwner(ids, fromOwnerId = GUEST, toOwnerId = ACCOUNT)

            assertEquals(ids.size, dao.getSessionsByOwner(ACCOUNT).size)
            assertTrue(dao.getSessionsByOwner(GUEST).isEmpty())
        }

    @Test
    fun sessionsOwnedByReadsTheReplacedGuestsRows() =
        runTest {
            insert(id = "guest-a", owner = GUEST, reps = 2)
            insert(id = "already-theirs", owner = ACCOUNT)

            // The merge reads the replaced guest's rows, not the signed-in user's.
            assertEquals(listOf("guest-a"), store.sessionsOwnedBy(GUEST).map { it.id })
        }

    @Test
    fun anUnknownExerciseNameIsSkippedRatherThanBreakingTheMerge() =
        runTest {
            insert(id = "guest-a", owner = GUEST)
            dao.insertSession(
                WorkoutSessionEntity(
                    id = "from-a-newer-build",
                    ownerId = GUEST,
                    exercise = "BURPEE",
                    startedAt = 3_000L,
                    endedAt = 4_000L,
                ),
            )

            assertEquals(listOf("guest-a"), store.sessionsOwnedBy(GUEST).map { it.id })
        }

    private suspend fun insert(
        id: String,
        owner: String,
        reps: Int = 0,
    ) {
        dao.insertSession(
            WorkoutSessionEntity(
                id = id,
                ownerId = owner,
                exercise = ExerciseType.SQUAT.name,
                startedAt = 1_000L,
                endedAt = 2_000L,
            ),
        )
        if (reps > 0) {
            dao.insertRepScores(
                List(reps) { index ->
                    RepScoreEntity(
                        sessionId = id,
                        repIndex = index + 1,
                        score = 7.5f,
                        tempoSeconds = 1.5f,
                        rangePercent = 90,
                        pauseSeconds = 0f,
                        reasons = "good depth",
                    )
                },
            )
        }
    }

    private suspend fun idsOwnedBy(owner: String): List<String> = dao.getSessionsByOwner(owner).map { it.id }

    private companion object {
        const val GUEST = "guest-uid"
        const val ACCOUNT = "account-uid"
        const val OTHER_ACCOUNT = "other-account-uid"
    }
}
