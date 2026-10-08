package com.repmate.ui.leaderboard

import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRepository
import com.repmate.data.repo.FriendRequest
import com.repmate.data.repo.LeaderboardEntry
import com.repmate.data.repo.LeaderboardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LeaderboardViewModel]'s friends tab, which had no test coverage at all: `LeaderboardLoadTest`
 * only ever exercised `observeTop`.
 *
 * The case that matters most is [addingAFriendRefreshesTheLeaderboard]. The friends list and the
 * leaderboard were collected one inside the other, and because the inner flow never completes for
 * a non-empty list, the outer lambda never returned and later friends-list changes were dropped:
 * adding a second friend showed nothing until the screen was reopened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest {
    private val friends = FakeFriendRepository()
    private val leaderboard = FakeLeaderboardRepository()
    private val auth = FakeAuth(uid = ME)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addingAFriendRefreshesTheLeaderboard() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            friends.friends.value = listOf(friend("alice"))
            leaderboard.friendEntries = listOf(entry("alice", 120))
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(listOf("alice"), viewModel.uiState.value.entries.map { it.userId })

            // The second friend: this is what the nested collect silently dropped.
            leaderboard.friendEntries = listOf(entry("alice", 120), entry("bob", 90))
            friends.friends.value = listOf(friend("alice"), friend("bob"))
            advanceUntilIdle()

            assertEquals(listOf("alice", "bob"), viewModel.uiState.value.entries.map { it.userId })
        }

    @Test
    fun removingAFriendRefreshesTheLeaderboardToo() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            friends.friends.value = listOf(friend("alice"), friend("bob"))
            leaderboard.friendEntries = listOf(entry("alice", 120), entry("bob", 90))
            val viewModel = viewModel()
            advanceUntilIdle()

            leaderboard.friendEntries = listOf(entry("alice", 120))
            friends.friends.value = listOf(friend("alice"))
            advanceUntilIdle()

            assertEquals(listOf("alice"), viewModel.uiState.value.entries.map { it.userId })
        }

    @Test
    fun theSignedInUserIsIncludedSoTheyCanSeeTheirOwnStanding() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            friends.friends.value = listOf(friend("alice"))
            viewModel()
            advanceUntilIdle()

            assertEquals(listOf(listOf("alice", ME)), leaderboard.requestedIds)
        }

    @Test
    fun theSignedInUserIsAskedForOnlyOnceEvenIfTheyAreAlsoAFriend() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // Shouldn't happen (addFriend refuses self) but a duplicate id would read the same
            // user twice and could list them twice.
            friends.friends.value = listOf(friend(ME), friend("alice"))
            viewModel()
            advanceUntilIdle()

            assertEquals(listOf(listOf(ME, "alice")), leaderboard.requestedIds)
        }

    @Test
    fun withNoFriendsTheUserStillSeesTheirOwnRow() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            friends.friends.value = emptyList()
            leaderboard.friendEntries = listOf(entry(ME, 50))
            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(listOf(ME), viewModel.uiState.value.entries.map { it.userId })
        }

    @Test
    fun aSignedOutUserAsksOnlyForTheirFriends() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            auth.uid = null
            friends.friends.value = listOf(friend("alice"))
            viewModel()
            advanceUntilIdle()

            assertEquals(listOf(listOf("alice")), leaderboard.requestedIds)
        }

    // NOTE: the failure path (a PERMISSION_DENIED turning into isUnavailable) is deliberately not
    // tested here. LeaderboardViewModel's Failed branch calls android.util.Log.w directly, which
    // throws "not mocked" in a JVM unit test, so the collector dies before the state is set and
    // the test would be asserting the test environment rather than the ViewModel. The wrapper that
    // turns a failed query into a LeaderboardLoad.Failed value is covered by LeaderboardLoadTest;
    // making the ViewModel's reaction testable needs its logging moved behind a seam, the way
    // LocalBestScoreSync and GuestMigrationLauncher already do it.

    @Test
    fun switchingToGlobalAndBackReloadsTheFriendsTab() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            friends.friends.value = listOf(friend("alice"))
            leaderboard.friendEntries = listOf(entry("alice", 120))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.selectLeaderboard(LeaderboardType.GLOBAL)
            advanceUntilIdle()
            assertEquals(LeaderboardType.GLOBAL, viewModel.uiState.value.selectedType)

            viewModel.selectLeaderboard(LeaderboardType.FRIENDS)
            advanceUntilIdle()

            assertEquals(LeaderboardType.FRIENDS, viewModel.uiState.value.selectedType)
            assertEquals(listOf("alice"), viewModel.uiState.value.entries.map { it.userId })
            assertFalse(viewModel.uiState.value.isUnavailable)
        }

    private fun viewModel() = LeaderboardViewModel(leaderboard, friends, auth)

    private companion object {
        const val ME = "my-uid"

        fun friend(userId: String) = Friend(userId = userId, displayName = userId)

        fun entry(
            userId: String,
            points: Int,
        ) = LeaderboardEntry(userId = userId, displayName = userId, points = points)
    }
}

private class FakeFriendRepository : FriendRepository {
    val friends = MutableStateFlow<List<Friend>>(emptyList())

    override fun observeFriends(): Flow<List<Friend>> = friends

    override suspend fun sendFriendRequest(displayName: String): Result<Unit> = Result.success(Unit)

    override fun observeFriendRequests(): Flow<List<FriendRequest>> = MutableStateFlow(emptyList())

    override suspend fun acceptFriendRequest(fromUserId: String): Result<Unit> = Result.success(Unit)

    override suspend fun rejectFriendRequest(fromUserId: String): Result<Unit> = Result.success(Unit)

    override suspend fun removeFriend(friendUserId: String): Result<Unit> = Result.success(Unit)
}

private class FakeLeaderboardRepository : LeaderboardRepository {
    var friendEntries: List<LeaderboardEntry> = emptyList()
    var failWith: Throwable? = null

    /** The id sets the ViewModel asked for, in order, so the own-row logic can be asserted. */
    val requestedIds = mutableListOf<List<String>>()

    override fun topPlayers(limit: Int): Flow<List<LeaderboardEntry>> = flow { emit(emptyList()) }

    override fun friendsLeaderboard(friendUserIds: List<String>): Flow<List<LeaderboardEntry>> =
        flow {
            requestedIds += friendUserIds
            failWith?.let { throw it }
            emit(friendEntries)
            // Deliberately stays open, like a Firestore snapshot listener. A flow that completed
            // here would let the old nested-collect implementation pass these tests: the bug it
            // had only shows up when the inner flow never finishes.
            awaitCancellation()
        }
}

private class FakeAuth(
    var uid: String?,
) : AuthRepository {
    override suspend fun signInAnonymously(): Result<String> = Result.failure(UnsupportedOperationException())

    override fun getCurrentUserId(): String? = uid
}
