package com.repmate.data.cloud

import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.repmate.data.repo.BestWorkoutScore
import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreGhostScoreDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository
) {

    /**
     * Publishes the current user's best score for an exercise.
     *
     * Only sessions with at least one rep are eligible.
     * A Firestore transaction prevents a lower score from
     * overwriting a higher score during concurrent updates.
     */
    suspend fun publishBestScore(
        session: WorkoutSession
    ): Result<Unit> {
        val userId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        if (session.reps.isEmpty()) {
            return Result.success(Unit)
        }

        val averageScore = session.reps
            .map { it.score.toDouble() }
            .average()

        val scoreRef = firestore
            .collection("users")
            .document(userId)
            .collection("ghostScores")
            .document(session.exercise.name)

        return try {
            firestore.runTransaction { transaction ->
                val existing = transaction.get(scoreRef)

                val existingScore =
                    existing.getDouble("averageScore")

                val existingReps =
                    existing.getLong("repCount")?.toInt() ?: 0

                val existingStartedAt =
                    existing.getLong("startedAt") ?: Long.MIN_VALUE

                val shouldUpdate =
                    existingScore == null ||
                            averageScore > existingScore ||
                            (
                                    averageScore == existingScore &&
                                            session.reps.size > existingReps
                                    ) ||
                            (
                                    averageScore == existingScore &&
                                            session.reps.size == existingReps &&
                                            session.startedAt > existingStartedAt
                                    )

                if (shouldUpdate) {
                    transaction.set(
                        scoreRef,
                        mapOf(
                            "exercise" to session.exercise.name,
                            "averageScore" to averageScore,
                            "repCount" to session.reps.size,
                            "startedAt" to session.startedAt
                        )
                    )
                }
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches an explicitly shared best score.
     *
     * Returns null when the user has no published
     * score for this exercise.
     */
    suspend fun getFriendBestScore(
        friendUid: String,
        exercise: ExerciseType
    ): Result<BestWorkoutScore?> {
        if (friendUid.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Friend UID cannot be empty")
            )
        }

        return try {
            val snapshot = firestore
                .collection("users")
                .document(friendUid)
                .collection("ghostScores")
                .document(exercise.name)
                .get()
                .await()

            if (!snapshot.exists()) {
                Result.success(null)
            } else {
                val score = snapshot.getDouble("averageScore")
                    ?: throw IllegalStateException(
                        "Missing averageScore"
                    )

                val reps = snapshot.getLong("repCount")
                    ?: throw IllegalStateException(
                        "Missing repCount"
                    )

                Result.success(
                    BestWorkoutScore(
                        score = score.toFloat(),
                        reps = reps.toInt()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
