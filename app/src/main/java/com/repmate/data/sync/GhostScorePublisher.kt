package com.repmate.data.sync

import com.repmate.engine.WorkoutSession

/**
 * The Ghost Duel side of a guest merge: offers one session as an account's best score.
 *
 * An interface so the migrator's tests can fake it (the real implementation talks to Firestore),
 * including making it fail on demand. Implementations must be safe to repeat: a resumed merge
 * publishes the same session again, and that must never lower or duplicate a stored score.
 */
interface GhostScorePublisher {
    /**
     * Offers [session] as the best score for its exercise under [targetUid]. The store only keeps
     * it if it beats what is already there. Sessions with no reps are ignored.
     *
     * Must fail, not write elsewhere, if [targetUid] is not the signed-in user at the moment of
     * the call: the merge reads the signed-in user once, and the user can change while it runs.
     * Implementations own their logging and report problems through the returned [Result]; they
     * may also throw, which the migrator treats the same way.
     */
    suspend fun publish(
        session: WorkoutSession,
        targetUid: String,
    ): Result<Unit>
}
