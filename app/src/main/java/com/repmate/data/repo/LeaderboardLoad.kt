package com.repmate.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * What a screen shows for the leaderboard: entries, or the fact that they could not be loaded.
 *
 * [LeaderboardRepository.topPlayers] reports a failure (a Firestore PERMISSION_DENIED, no
 * network, a missing collection) by failing the flow, which is the honest contract for a
 * repository. But a flow that fails inside a `viewModelScope.launch { collect }` takes the whole
 * process down with it, and nothing about the leaderboard is worth that: every collector should
 * read the flow through [observeTop] instead, so a failure is a value the screen renders.
 */
sealed interface LeaderboardLoad {
    data class Loaded(val entries: List<LeaderboardEntry>) : LeaderboardLoad

    data class Failed(val cause: Throwable) : LeaderboardLoad
}

/**
 * [LeaderboardRepository.topPlayers] with its failure turned into a final [LeaderboardLoad.Failed]
 * item instead of an exception. Entries that arrived before the failure are still delivered, and
 * cancelling the collector is still cancellation, not a failure.
 */
fun LeaderboardRepository.observeTop(limit: Int): Flow<LeaderboardLoad> =
    topPlayers(limit)
        .map<List<LeaderboardEntry>, LeaderboardLoad> { LeaderboardLoad.Loaded(it) }
        .catch { emit(LeaderboardLoad.Failed(it)) }
