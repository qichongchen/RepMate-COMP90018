package com.repmate.data.cloud

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreWorkoutDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository,
) {

    private companion object {
        const val TAG = "FirestoreWorkout"
    }

    suspend fun upload(session: WorkoutSession): Result<Unit> {
        val userId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        return try {
            val reps = session.reps.map { rep ->
                mapOf(
                    "repIndex" to rep.repIndex,
                    "score" to rep.score,
                    "tempoSeconds" to rep.tempoSeconds,
                    "rangePercent" to rep.rangePercent,
                    "pauseSeconds" to rep.pauseSeconds,
                    "reasons" to rep.reasons,
                )
            }

            val averageScore =
                if (session.reps.isEmpty()) {
                    0.0
                } else {
                    session.reps.map { it.score.toDouble() }.average()
                }

            val workoutData = mapOf(
                "id" to session.id,
                "userId" to userId,
                "exercise" to session.exercise.name,
                "startedAt" to session.startedAt,
                "endedAt" to session.endedAt,
                "repCount" to session.reps.size,
                "averageScore" to averageScore,
                "reps" to reps,
                "updatedAt" to FieldValue.serverTimestamp(),
            )

            firestore
                .collection("users")
                .document(userId)
                .collection("workoutSessions")
                .document(session.id)
                .set(workoutData)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to upload workout ${session.id}",
                e
            )

            Result.failure(e)
        }
    }
}