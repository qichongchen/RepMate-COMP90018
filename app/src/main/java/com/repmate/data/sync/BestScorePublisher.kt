package com.repmate.data.sync

import com.repmate.engine.WorkoutSession

/**
 * Offers one session as the signed-in user's Ghost Duel best score for its exercise.
 *
 * The one method of `FirestoreGhostScoreDataSource` that [LocalBestScoreSync] needs, pulled out as
 * an interface so that class can be tested without a FirebaseFirestore instance. Implementations
 * must only ever replace a stored score with a better one, which is what makes repeating a
 * publish harmless.
 */
interface BestScorePublisher {
    /** Publishes [session] under the signed-in user. Sessions with no reps are ignored. */
    suspend fun publishBestScore(session: WorkoutSession): Result<Unit>
}
