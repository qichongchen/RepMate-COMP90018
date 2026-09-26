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

    suspend fun fetchWorkoutSessions(
        userId: String
    ): Result<List<FirestoreWorkoutSession>> {
        return try {
            val snapshot = firestore
                .collection("users")
                .document(userId)
                .collection("workoutSessions")
                .get()
                .await()

            val sessions = snapshot.documents.mapNotNull { document ->
                try {
                    val id = document.getString("id")
                        ?: document.id

                    val storedUserId = document.getString("userId")
                        ?: userId

                    val exercise = document.getString("exercise")
                        ?: return@mapNotNull null

                    val startedAt = document.getLong("startedAt")
                        ?: return@mapNotNull null

                    val endedAt = document.getLong("endedAt")

                    val rawReps =
                        document.get("reps") as? List<*>
                            ?: emptyList<Any>()

                    val reps = rawReps.mapNotNull repLoop@{ rawRep ->
                        val repMap =
                            rawRep as? Map<*, *>
                                ?: return@repLoop null

                        val repIndex =
                            (repMap["repIndex"] as? Number)?.toInt()
                                ?: return@repLoop null

                        val score =
                            (repMap["score"] as? Number)?.toInt()
                                ?: return@repLoop null

                        val tempoSeconds =
                            (repMap["tempoSeconds"] as? Number)?.toDouble()
                                ?: 0.0

                        val rangePercent =
                            (repMap["rangePercent"] as? Number)?.toDouble()
                                ?: 0.0

                        val pauseSeconds =
                            (repMap["pauseSeconds"] as? Number)?.toDouble()
                                ?: 0.0

                        val reasons =
                            (repMap["reasons"] as? List<*>)
                                ?.filterIsInstance<String>()
                                ?: emptyList()

                        FirestoreRepScore(
                            repIndex = repIndex,
                            score = score,
                            tempoSeconds = tempoSeconds,
                            rangePercent = rangePercent,
                            pauseSeconds = pauseSeconds,
                            reasons = reasons,
                        )
                    }

                    FirestoreWorkoutSession(
                        id = id,
                        userId = storedUserId,
                        exercise = exercise,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        reps = reps,
                    )
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Skipping invalid workout document ${document.id}",
                        e
                    )

                    null
                }
            }

            Result.success(sessions)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to fetch workouts for user $userId",
                e
            )

            Result.failure(e)
        }
    }
}