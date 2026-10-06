package com.repmate.data.cloud

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRepository
import com.repmate.data.repo.FriendRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreFriendRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository,
) : FriendRepository {

    private companion object {
        const val TAG = "FirestoreFriends"
    }

    override suspend fun sendFriendRequest(
        displayName: String
    ): Result<Unit> {
        val currentUserId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        val name = displayName.trim()

        if (name.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Display name cannot be empty")
            )
        }

        return try {
            // Search for the target user by display name.
            val usernameDocument = firestore
                .collection("usernames")
                .document(name.lowercase())
                .get()
                .await()
            if (!usernameDocument.exists()) {
                return Result.failure(
                    IllegalArgumentException("User not found")
                )
            }
            val friendId = usernameDocument.getString("uid")
                ?: return Result.failure(
                    IllegalArgumentException("User not found")
                )

            if (friendId == currentUserId) {
                return Result.failure(
                    IllegalArgumentException("You cannot add yourself")
                )
            }

            val existingFriend = firestore
                .collection("users")
                .document(currentUserId)
                .collection("friends")
                .document(friendId)
                .get()
                .await()

            if (existingFriend.exists()) {
                return Result.failure(
                    IllegalArgumentException("You are already friends")
                )
            }

            val existingRequest = firestore
                .collection("users")
                .document(friendId)
                .collection("friendRequests")
                .document(currentUserId)
                .get()
                .await()

            if (existingRequest.exists()) {
                return Result.failure(
                    IllegalArgumentException("Friend request already sent")
                )
            }
            // Get the current user's display name.
            val currentUserProfile = firestore
                .collection("users")
                .document(currentUserId)
                .get()
                .await()

            val currentDisplayName = currentUserProfile
                .getString("displayName")
                ?: "RepMate User"

            // Store the request under the recipient.
            val requestData = mapOf(
                "fromUserId" to currentUserId,
                "displayName" to currentDisplayName,
                "createdAt" to FieldValue.serverTimestamp()
            )

            firestore
                .collection("users")
                .document(friendId)
                .collection("friendRequests")
                .document(currentUserId)
                .set(requestData)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send friend request", e)
            Result.failure(e)
        }
    }

    override fun observeFriendRequests(): Flow<List<FriendRequest>> =
        callbackFlow {
            val currentUserId = authRepository.getCurrentUserId()

            if (currentUserId == null) {
                close(IllegalStateException("No authenticated user"))
                return@callbackFlow
            }

            val listener = firestore
                .collection("users")
                .document(currentUserId)
                .collection("friendRequests")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Failed to observe friend requests", error)
                        close(error)
                        return@addSnapshotListener
                    }

                    val requests = snapshot?.documents
                        ?.map { document ->
                            FriendRequest(
                                userId = document.getString("fromUserId")
                                    ?: document.id,
                                displayName = document.getString("displayName")
                                    ?: "RepMate User"
                            )
                        }
                        .orEmpty()

                    trySend(requests)
                }

            awaitClose {
                listener.remove()
            }
        }


    override suspend fun acceptFriendRequest(
        fromUserId: String
    ): Result<Unit> {
        val currentUserId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        val senderId = fromUserId.trim()

        if (senderId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("User ID cannot be empty")
            )
        }

        return try {
            // Get both user profiles so each friend entry has the correct display name.
            val currentUserProfile = firestore
                .collection("users")
                .document(currentUserId)
                .get()
                .await()

            val senderProfile = firestore
                .collection("users")
                .document(senderId)
                .get()
                .await()

            if (!senderProfile.exists()) {
                return Result.failure(
                    IllegalArgumentException("User not found")
                )
            }

            val currentDisplayName =
                currentUserProfile.getString("displayName")
                    ?: "RepMate User"

            val senderDisplayName =
                senderProfile.getString("displayName")
                    ?: "RepMate User"

            val currentUserFriendRef = firestore
                .collection("users")
                .document(currentUserId)
                .collection("friends")
                .document(senderId)

            val senderFriendRef = firestore
                .collection("users")
                .document(senderId)
                .collection("friends")
                .document(currentUserId)

            val requestRef = firestore
                .collection("users")
                .document(currentUserId)
                .collection("friendRequests")
                .document(senderId)

            val batch = firestore.batch()

            // Sender appears in the current user's friend list.
            batch.set(
                currentUserFriendRef,
                mapOf(
                    "friendUserId" to senderId,
                    "displayName" to senderDisplayName,
                    "addedAt" to FieldValue.serverTimestamp()
                )
            )
            // Current user appears in the sender's friend list.
            batch.set(
                senderFriendRef,
                mapOf(
                    "friendUserId" to currentUserId,
                    "displayName" to currentDisplayName,
                    "addedAt" to FieldValue.serverTimestamp()
                )
            )
            // Request is no longer pending.
            batch.delete(requestRef)

            batch.commit().await()

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept friend request", e)
            Result.failure(e)
        }
    }

    override suspend fun rejectFriendRequest(
        fromUserId: String
    ): Result<Unit> {
        val currentUserId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        val senderId = fromUserId.trim()

        if (senderId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("User ID cannot be empty")
            )
        }

        return try {
            firestore
                .collection("users")
                .document(currentUserId)
                .collection("friendRequests")
                .document(senderId)
                .delete()
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject friend request", e)
            Result.failure(e)
        }
    }

    override fun observeFriends(): Flow<List<Friend>> =
        callbackFlow {
            val currentUserId = authRepository.getCurrentUserId()

            if (currentUserId == null) {
                close(IllegalStateException("No authenticated user"))
                return@callbackFlow
            }

            val listener = firestore
                .collection("users")
                .document(currentUserId)
                .collection("friends")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Failed to observe friends", error)
                        close(error)
                        return@addSnapshotListener
                    }

                    val friends = snapshot?.documents
                        ?.map { document ->
                            Friend(
                                userId = document.id,
                                displayName = document
                                    .getString("displayName")
                                    ?: "RepMate User"
                            )
                        }
                        .orEmpty()

                    trySend(friends)
                }

            awaitClose {
                listener.remove()
            }
        }

    override suspend fun removeFriend(
        friendUserId: String
    ): Result<Unit> {
        val currentUserId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        val friendId = friendUserId.trim()
        if (friendId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Friend UID cannot be empty")
            )
        }

        return try {
            val currentUserFriendRef = firestore
                .collection("users")
                .document(currentUserId)
                .collection("friends")
                .document(friendId)

            val friendUserRef = firestore
                .collection("users")
                .document(friendId)
                .collection("friends")
                .document(currentUserId)

            val batch = firestore.batch()

            batch.delete(currentUserFriendRef)
            batch.delete(friendUserRef)
            batch.commit().await()
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove friend", e)
            Result.failure(e)
        }
    }
}