package com.repmate.data.repo

/**
 * Represents a workout's best session score for Ghost Duel.
 *
 * @property score The average score of the best workout session.
 * @property reps The number of repetitions in that session.
 */
data class BestWorkoutScore(
    val score: Float,
    val reps: Int
)
