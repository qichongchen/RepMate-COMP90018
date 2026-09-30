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
 *    marker if the UID did not change, meaning no switch happened).
 * 3. [resume] -- **upload, then re-own, then clear**:
 *    a. upload every guest session (including 0-rep ones: still the user's exercise data) to the target
 *    account (`set`, an upsert);
 *    b. only if *every* upload succeeded, re-own those sessions in Room;
 *    c. only then clear the marker.
 *
 * Re-owning is the last local step because `workout_sessions` is keyed by `id` alone: once a row
 * is re-owned it no longer shows up as the guest's, so it could never be found and uploaded on a
 * retry. Uploading first means any failure leaves every session with the guest, marker intact,
 * and the whole thing is safely repeatable: repeat uploads just overwrite, and re-owning a
 * session that already moved matches nothing.
 *
 * ## Deliberately not done
 * - Calibration profiles are not merged: the account keeps its own.
 * - The guest's own Firestore documents (uploaded while they were a guest) are not read or
 *   deleted; cleaning those up would need a privileged backend job, since the rules only let a
 *   user touch their own documents.
 * - TODO(ghost-duel): the account's Ghost Duel best score is not republished after a merge,
 *   because `FirestoreGhostScoreDataSource.publishBestScore` is not called from anywhere in the
 *   app today. Wire it here for each exercise with reps once it is wired for normal workouts.
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

        /** Persists [guestUid] as a pending merge. Call immediately before a sign-in that replaces the guest. */
        suspend fun markPending(guestUid: String) =
            mutex.withLock {
                markerStore.save(PendingGuestMigration(guestUid = guestUid))
            }

        /**
         * Drops the marker written by [markPending] because the sign-in failed or was cancelled.
         * Does nothing once a target has been recorded, i.e. once the sign-in has succeeded.
         */
        suspend fun discardUnresolvedCapture() =
            mutex.withLock {
                val pending = markerStore.get()
                if (pending != null && pending.targetUid == null) markerStore.clear()
            }

        /**
         * Call right after a replacing sign-in succeeds. If the signed-in UID equals the guest's,
         * no account switch happened, so the marker is cleared and nothing is migrated. Otherwise
         * the signed-in account is recorded as the target.
         */
        suspend fun recordSignIn() =
            mutex.withLock {
                val pending = markerStore.get() ?: return@withLock
                val current = authRepository.getCurrentUserId() ?: return@withLock
                if (current == pending.guestUid) {
                    markerStore.clear()
                } else if (pending.targetUid == null) {
                    markerStore.save(pending.copy(targetUid = current))
                }
            }

        /**
         * Runs the pending merge, if any and if it is this account's to run.
         *
         * @return the number of sessions merged (0 when there was nothing to do), or a failure
         *   with the marker still in place so a later call can retry.
         */
        suspend fun resume(): Result<Int> =
            mutex.withLock {
                try {
                    val pending = markerStore.get() ?: return@withLock Result.success(0)
                    // Signed out: nothing to merge into yet. Keep the marker.
                    val current = authRepository.getCurrentUserId() ?: return@withLock Result.success(0)
                    if (current == pending.guestUid) {
                        // Still the guest, so no account switch happened.
                        markerStore.clear()
                        return@withLock Result.success(0)
                    }
                    // A UID that differs from the guest's means a sign-in did succeed, even if the
                    // app died before recordSignIn could note the target: adopt it.
                    val target = pending.targetUid ?: current.also { markerStore.save(pending.copy(targetUid = it)) }
                    // A different account than the one logged in to is signed in now. Leave the
                    // marker (no expiry); the guest's workouts simply stay hidden.
                    if (current != target) return@withLock Result.success(0)
                    merge(pending.guestUid, target)
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
                    // All-or-nothing: leave every session with the guest and keep the marker.
                    return Result.failure(uploaded.exceptionOrNull() ?: IllegalStateException("Upload failed"))
                }
            }
            guestSessions.reassignOwner(toMerge.map { it.id }, guestUid, targetUid)
            markerStore.clear()
            return Result.success(toMerge.size)
        }
    }
