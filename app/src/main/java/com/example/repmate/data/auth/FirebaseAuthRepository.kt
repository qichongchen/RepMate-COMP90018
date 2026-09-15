package com.example.repmate.data.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class FirebaseAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) : AuthRepository {

    override suspend fun signInAnonymously(): Result<String> {
        return try {
            val result = firebaseAuth
                .signInAnonymously()
                .await()

            val uid = result.user?.uid
                ?: return Result.failure(
                    IllegalStateException("Firebase UID is missing")
                )

            Result.success(uid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getCurrentUserId(): String? {
        return firebaseAuth.currentUser?.uid
    }
}