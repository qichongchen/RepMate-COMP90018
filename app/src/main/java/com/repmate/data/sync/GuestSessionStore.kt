package com.repmate.data.sync

import com.repmate.engine.WorkoutSession

/**
 * The local (Room) side of a guest merge, for an owner other than the signed-in user.
 *
 * Separate from [com.repmate.data.repo.SessionRepository] because that interface is deliberately
 * scoped to the *current* user; the merge has to read the *guest's* rows after the guest has
 * already been replaced. An interface so the migrator's tests can fake it.
 */
interface GuestSessionStore {
    /** Every session stored under [ownerId], with its rep scores. May include 0-rep sessions. */
    suspend fun sessionsOwnedBy(ownerId: String): List<WorkoutSession>

    /**
     * Hands the sessions [sessionIds] over from [fromOwnerId] to [toOwnerId]. Touches only the
     * owner column; rep rows are untouched. Idempotent: ids no longer owned by [fromOwnerId] are
     * ignored, so a repeat call changes nothing.
     */
    suspend fun reassignOwner(
        sessionIds: List<String>,
        fromOwnerId: String,
        toOwnerId: String,
    )
}
