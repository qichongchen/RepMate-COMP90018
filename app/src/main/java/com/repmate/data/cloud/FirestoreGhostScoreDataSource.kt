package com.repmate.data.cloud

import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.repmate.data.repo.BestWorkoutScore
import com.repmate.data.sync.BestScorePublisher
import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Averages closer than this count as equal. Shared with the guest migrator, which must pick the
 * same "best" session the store would keep.
 */
internal const val SCORE_EPSILON = 1e-6

@Singleton
class FirestoreGhostScoreDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository
) : BestScorePublisher {

    /**
     * Publishes the current user's best score for an exercise.
     *
     * Only sessions with at least one rep are eligible.
     * A Firestore transaction prevents a lower score from
     * overwriting a higher score during concurrent updates.
     */
    override suspend fun publishBestScore(
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

                // Treat tiny floating-point differences as equal.
                // When scores are tied, prefer more reps, then the newer session.
                val shouldUpdate =
                    if (existingScore == null) {
                        true
                    } else {
                        val scoreDifference = averageScore - existingScore

                        when {
                            scoreDifference > SCORE_EPSILON -> true

                            abs(scoreDifference) <= SCORE_EPSILON ->
                                session.reps.size > existingReps ||
                                        (
                                                session.reps.size == existingReps &&
                                                        session.startedAt > existingStartedAt
                                                )

                            else -> false
                        }
                    }

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
