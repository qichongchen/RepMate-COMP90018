package com.repmate.data.repo

import kotlinx.coroutines.flow.Flow

interface FriendRepository {

    suspend fun addFriend(friendUserId: String): Result<Unit>

    fun observeFriends(): Flow<List<Friend>>

    suspend fun removeFriend(friendUserId: String): Result<Unit>
}