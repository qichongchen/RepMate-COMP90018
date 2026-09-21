package com.repmate.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [observeTop] is what stands between a Firestore failure and a crashed app: `topPlayers` fails
 * its flow on PERMISSION_DENIED, and a failed flow collected in `viewModelScope.launch` kills the
 * process. These tests use the same shape of failure (an exception thrown from inside the flow).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardLoadTest {

    private class FakeLeaderboardRepository(private val source: () -> Flow<List<LeaderboardEntry>>) : LeaderboardRepository {
        override fun topPlayers(limit: Int): Flow<List<LeaderboardEntry>> = source()
    }

    private val priya = LeaderboardEntry(userId = "u1", displayName = "Priya", points = 1420)
    private val marcus = LeaderboardEntry(userId = "u2", displayName = "Marcus", points = 1260)

    @Test
    fun `entries come through as Loaded`() = runTest {
        val repo = FakeLeaderboardRepository { flow { emit(listOf(priya, marcus)) } }

        assertEquals(listOf<LeaderboardLoad>(LeaderboardLoad.Loaded(listOf(priya, marcus))), repo.observeTop(3).toList())
    }

    @Test
    fun `a permission-denied failure becomes a Failed value instead of an exception`() = runTest {
        val denied = SecurityException("PERMISSION_DENIED: Missing or insufficient permissions.")
        val repo = FakeLeaderboardRepository { flow { throw denied } }

        // Collecting must not throw: an exception here is the crash.
        val loads = repo.observeTop(3).toList()

        assertEquals(1, loads.size)
        assertSame(denied, assertIs<LeaderboardLoad.Failed>(loads.single()).cause)
    }

    @Test
    fun `entries delivered before a failure are kept, then the failure follows`() = runTest {
        val repo = FakeLeaderboardRepository {
            flow {
                emit(listOf(priya))
                throw IllegalStateException("listener died")
            }
        }

        val loads = repo.observeTop(3).toList()

        assertEquals(LeaderboardLoad.Loaded(listOf(priya)), loads[0])
        assertIs<LeaderboardLoad.Failed>(loads[1])
        assertEquals(2, loads.size)
    }

    @Test
    fun `a failure while building the query is caught too`() = runTest {
        // e.g. Firestore not initialised: thrown from topPlayers itself, not from inside the flow.
        val repo = FakeLeaderboardRepository { throw IllegalStateException("FirebaseApp not initialized") }

        val loads = runCatching { repo.observeTop(3).toList() }

        // Documents the boundary rather than hiding it: a throw *before* a flow exists is not a
        // flow failure, so it is not this operator's to catch. Firestore's builder is lazy and
        // FirestoreLeaderboardRepository builds its query inside callbackFlow, so it cannot occur
        // there; if a repository ever did this it would need its own guard.
        assertIs<IllegalStateException>(loads.exceptionOrNull())
    }

    @Test
    fun `cancelling the collector is not reported as a failure`() = runTest {
        val seen = mutableListOf<LeaderboardLoad>()
        val repo = FakeLeaderboardRepository { flow { emit(listOf(priya)); kotlinx.coroutines.awaitCancellation() } }

        val job = launch { repo.observeTop(3).collect { seen += it } }
        testScheduler.advanceUntilIdle()
        job.cancel(CancellationException("screen left"))
        job.join()

        assertEquals(listOf<LeaderboardLoad>(LeaderboardLoad.Loaded(listOf(priya))), seen)
    }
}
