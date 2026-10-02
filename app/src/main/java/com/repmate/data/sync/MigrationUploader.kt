package com.repmate.data.sync

import com.repmate.engine.WorkoutSession

/**
 * The cloud side of a guest merge: writes one session to the target account's Firestore.
 *
 * An interface so the migrator's tests can fake it, including making it fail on demand.
 * Implementations must be idempotent (an upsert), because a resumed merge re-uploads sessions
 * that may already be there.
 */
interface MigrationUploader {
    /**
     * Upserts [session] under `users/{targetUid}/workoutSessions/{session.id}`.
     * Must fail, not write elsewhere, if [targetUid] is not the signed-in user.
     */
    suspend fun upload(
        session: WorkoutSession,
        targetUid: String,
    ): Result<Unit>
}
