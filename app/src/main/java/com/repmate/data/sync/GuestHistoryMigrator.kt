package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges a guest's local workout history into the account they logged in to.
 *
 * ## When it applies
 * Only when a sign-in *replaces* the guest (Log in with email or Google; Google on Sign up
 * outside upgrade mode). Upgrading a guest links the credential to the same anonymous user, so
 * the UID never changes and the history is already in the right place -- no merge, no marker.
 *
 * ## The protocol (and why it is in this order)
 * 1. [markPending] -- before the sign-in call, persist the guest's UID. It is unrecoverable
 *    afterwards. [discardUnresolvedCapture] undoes this if the sign-in fails.
 * 2. [recordSignIn] -- once sign-in succeeds, record which account is the target (or drop the
 *    entry if the UID did not change, meaning no switch happened).
 * 3. [resume] -- **upload, then re-own, then forget**:
 *    a. upload every guest session (including 0-rep ones: still the user's exercise data) to the target
 *    account (`set`, an upsert);
 *    b. only if *every* upload succeeded, re-own those sessions in Room;
 *    c. only then drop that guest's entry.
 *
 * Re-owning is the last local step because `workout_sessions` is keyed by `id` alone: once a row
 * is re-owned it no longer shows up as the guest's, so it could never be found and uploaded on a
 * retry. Uploading first means any failure leaves every session with the guest, the entry intact,
 * and the whole thing is safely repeatable: repeat uploads just overwrite, and re-owning a
 * session that already moved matches nothing.
 *
 * ## More than one pending merge at a time
 * [PendingMigrationStore] keeps every unfinished merge, not only the latest. A guest who logs in
 * while offline, signs out, uses the app as a guest again and logs in a second time leaves two
 * entries, and both get merged -- the first guest's rows used to be orphaned in Room the moment
 * the second capture overwrote the first marker. Hence:
 * - [markPending] adds an entry and never touches another guest's;
 * - [discardUnresolvedCapture] names the guest it discards, so a failed sign-in cannot drop an
 *   entry captured before it;
 * - [resume] walks every entry, and one entry that keeps failing does not strand the others.
 *
 * ## Deliberately not done
 * - Calibration profiles are not merged: the account keeps its own.
 * - The guest's own Firestore documents (uploaded while they were a guest) are not read or
 *   deleted; cleaning those up would need a privileged backend job, since the rules only let a
 *   user touch their own documents. The merge reads the **Room** copy for that same reason: a
 *   guest's cloud documents are unreachable once the guest is gone, and no rule should be widened
 *   to reach them, so a session that exists only in Firestore is not recovered.
 * - TODO(ghost-duel): the account's Ghost Duel best score is not republished after a merge.
 *   `publishBestScore` became reachable with normal workout sync in #48; wire it in here too, for
 *   each exercise with reps.
 *
 * All calls are serialised by a mutex, so a resume at app start and one right after sign-in
 * can never run the merge concurrently.
 */
@Singleton
class GuestHistoryMigrator
    @Inject
    constructor(
        private val markerStore: PendingMigrationStore,
        private val guestSessions: GuestSessionStore,
        private val uploader: MigrationUploader,
        private val authRepository: AuthRepository,
    ) {
        private val mutex = Mutex()

        /**
         * Records [guestUid] as a pending merge. Call immediately before a sign-in that replaces
         * the guest.
         *
         * An entry that already exists for this guest is left alone, so a retried sign-in cannot
         * wipe a target an earlier successful one recorded.
         */
        suspend fun markPending(guestUid: String) =
            mutex.withLock {
                if (markerStore.all().none { it.guestUid == guestUid }) {
                    markerStore.save(PendingGuestMigration(guestUid = guestUid))
                }
            }

        /**
         * Drops [guestUid]'s entry because the sign-in failed or was cancelled. Does nothing once
         * a target has been recorded for it, i.e. once that sign-in has succeeded, and never
         * touches another guest's entry.
         */
        suspend fun discardUnresolvedCapture(guestUid: String) =
            mutex.withLock {
                val pending = markerStore.all().firstOrNull { it.guestUid == guestUid } ?: return@withLock
                if (pending.targetUid == null) markerStore.remove(guestUid)
            }

        /**
         * Call right after a replacing sign-in succeeds. An entry whose guest *is* the signed-in
         * UID means no account switch happened, so it is dropped and nothing is migrated for it.
         * Any entry still without a target takes the signed-in account: every entry here was
         * captured on this device immediately before a sign-in, and this is the account those
         * sign-ins ended at.
         */
        suspend fun recordSignIn() =
            mutex.withLock {
                val current = authRepository.getCurrentUserId() ?: return@withLock
                markerStore.all().forEach { pending ->
                    when {
                        pending.guestUid == current -> markerStore.remove(pending.guestUid)
                        pending.targetUid == null -> markerStore.save(pending.copy(targetUid = current))
                    }
                }
            }

        /**
         * Runs every pending merge that is this account's to run.
         *
         * @return the total number of sessions merged (0 when there was nothing to do). A failure
         *   carries the first error, with that entry still stored so a later call can retry; any
         *   other entry that succeeded has already been merged and dropped.
         */
        suspend fun resume(): Result<Int> =
            mutex.withLock {
                try {
                    // Signed out: nothing to merge into yet. Keep every entry.
                    val current = authRepository.getCurrentUserId() ?: return@withLock Result.success(0)
                    var merged = 0
                    var firstFailure: Throwable? = null

                    for (pending in markerStore.all()) {
                        if (pending.guestUid == current) {
                            // Still that guest, so no account switch happened.
                            markerStore.remove(pending.guestUid)
                            continue
                        }
                        // A UID that differs from the guest's means a sign-in did succeed, even if
                        // the app died before recordSignIn could note the target: adopt it.
                        val target =
                            pending.targetUid
                                ?: current.also { markerStore.save(pending.copy(targetUid = it)) }
                        // A different account than the one logged in to is signed in now. Leave the
                        // entry (no expiry); the guest's workouts simply stay hidden until then.
                        if (current != target) continue

                        merge(pending.guestUid, target)
                            .onSuccess { merged += it }
                            // Keep going: one entry that cannot be uploaded must not strand the rest.
                            .onFailure { error -> if (firstFailure == null) firstFailure = error }
                    }

                    firstFailure?.let { Result.failure<Int>(it) } ?: Result.success(merged)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

        private suspend fun merge(
            guestUid: String,
            targetUid: String,
        ): Result<Int> {
            val toMerge: List<WorkoutSession> = guestSessions.sessionsOwnedBy(guestUid)
            for (session in toMerge) {
                val uploaded = uploader.upload(session, targetUid)
                if (uploaded.isFailure) {
                    // All-or-nothing: leave every session with the guest and keep the entry.
                    return Result.failure(uploaded.exceptionOrNull() ?: IllegalStateException("Upload failed"))
                }
            }
            guestSessions.reassignOwner(toMerge.map { it.id }, guestUid, targetUid)
            markerStore.remove(guestUid)
            return Result.success(toMerge.size)
        }
    }
