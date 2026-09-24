package com.repmate.data.cloud

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRepository
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

    override suspend fun addFriend(
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

        if (friendId == currentUserId) {
            return Result.failure(
                IllegalArgumentException("You cannot add yourself")
            )
        }

        return try {
            // Verify that the target user has a Firestore profile.
            val friendProfile = firestore
                .collection("users")
                .document(friendId)
                .get()
                .await()

            if (!friendProfile.exists()) {
                return Result.failure(
                    IllegalArgumentException("User not found")
                )
            }

            val displayName = friendProfile
                .getString("displayName")
                ?: "RepMate User"

            // Using the friend's UID as the document ID
            // prevents duplicate friend documents.
            val friendData = mapOf(
                "friendUserId" to friendId,
                "displayName" to displayName,
                "addedAt" to FieldValue.serverTimestamp()
            )

            firestore
                .collection("users")
                .document(currentUserId)
                .collection("friends")
                .document(friendId)
                .set(friendData)
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add friend", e)
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
            firestore
                .collection("users")
                .document(currentUserId)
                .collection("friends")
                .document(friendId)
                .delete()
                .await()

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove friend", e)
            Result.failure(e)
        }
    }
}