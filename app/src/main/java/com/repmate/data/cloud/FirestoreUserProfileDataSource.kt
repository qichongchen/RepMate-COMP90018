
package com.repmate.data.cloud

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreUserProfileDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val firebaseAuth: FirebaseAuth,
) {
    private companion object {
        const val TAG = "FirestoreUserProfile"
    }

    suspend fun ensureCurrentUserProfile(): Result<Unit> {
        val user = firebaseAuth.currentUser
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        return try {
            val userRef = firestore
                .collection("users")
                .document(user.uid)

            // Do not overwrite an existing profile.
            val existingProfile = userRef.get().await()

            if (!existingProfile.exists()) {
                val displayName = user.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: "RepMate User"

                val profile = mapOf(
                    "displayName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp(),
                )

                userRef.set(
                    profile,
                    SetOptions.merge()
                ).await()

                Log.d(TAG, "User profile created")
            } else {
                Log.d(TAG, "User profile already exists")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure user profile", e)
            Result.failure(e)
        }
    }
}