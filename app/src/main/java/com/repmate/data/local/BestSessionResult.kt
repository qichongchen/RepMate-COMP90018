package com.repmate.data.local

/**
 * Represents the best workout session retrieved from the local database.
 *
 * @property averageScore The average score of the session.
 * @property repCount The number of repetitions completed in the session.
 */
data class BestSessionResult(
    val averageScore: Float,
    val repCount: Int
)
