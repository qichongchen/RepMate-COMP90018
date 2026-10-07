package com.repmate.data.sync

import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession

/**
 * Reads the signed-in user's best session for an exercise from the local (Room) copy.
 *
 * An interface so [LocalBestScoreSync] can be unit tested without a database.
 */
interface LocalBestSessionSource {
    /**
     * The current user's best session for [exercise], with its reps: highest average rep score,
     * then more reps, then newer `startedAt`. Sessions with no reps are never returned.
     *
     * Only the signed-in user's own sessions are considered, never a guest's or another account's.
     * Returns null when there is no such session or nobody is signed in.
     */
    suspend fun bestSessionFor(exercise: ExerciseType): WorkoutSession?
}
