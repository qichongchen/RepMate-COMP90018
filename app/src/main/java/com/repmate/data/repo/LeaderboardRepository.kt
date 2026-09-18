package com.repmate.data.repo

import kotlinx.coroutines.flow.Flow

data class LeaderboardEntry(
    val userId: String,
    val displayName: String,
    val points: Int,
)

interface LeaderboardRepository {
    fun topPlayers(limit: Int): Flow<List<LeaderboardEntry>>
}