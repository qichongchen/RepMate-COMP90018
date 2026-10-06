package com.repmate.data.repo

import kotlinx.coroutines.flow.Flow


interface FriendRepository {

    // Search by display name and send a friend request.
    suspend fun sendFriendRequest(displayName: String): Result<Unit>

    // Requests received by the current user.
    fun observeFriendRequests(): Flow<List<FriendRequest>>

    // Accept a request and create the friendship for both users.
    suspend fun acceptFriendRequest(fromUserId: String): Result<Unit>

    // Reject/delete a pending request.
    suspend fun rejectFriendRequest(fromUserId: String): Result<Unit>

    fun observeFriends(): Flow<List<Friend>>

    // Remove the friendship for both users.
    suspend fun removeFriend(friendUserId: String): Result<Unit>
}

