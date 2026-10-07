package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.cloud.SCORE_EPSILON
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
 *    b. only if *every* upload succeeded, republish the account's Ghost Duel best score (below);
 *    c. then re-own those sessions in Room;
 *    d. only then drop that guest's entry.
 *
 * Re-owning is the last local step because `workout_sessions` is keyed by `id` alone: once a row
 * is re-owned it no longer shows up as the guest's, so it could never be found and uploaded on a
 * retry. Uploading first means any failure leaves every session with the guest, the entry intact,
 * and the whole thing is safely repeatable: repeat uploads just overwrite, and re-owning a
 * session that already moved matches nothing.
 *
 * ## Ghost Duel best score
 * Sessions that arrive through a merge never pass through `SyncingSessionRepository.save`, which
 * is the only other place a best score is published, so the merge publishes it itself:
 * - **Best session per exercise, once per exercise**, not once per session. Sessions with no reps
 *   are ignored. "Best" is the order the store itself uses: higher average rep score, then more
 *   reps, then the newer `startedAt`.
 * - **Best-effort.** A failed or throwing publish is ignored here (the publisher logs it): the
 *   sessions are already safe in the account's Firestore, so a missing Ghost Duel score must not
 *   leave them stranded with the guest or keep the entry alive.
 * - **Safe to repeat.** It runs before the re-own and the entry removal, so if the app dies in
 *   between, the next [resume] runs this merge again with the same sessions and publishes again.
 *   The store only overwrites a score with a better one, so the repeat changes nothing.
 * - **Right account only.** [resume] checks the signed-in user is the merge target once, up front,
 *   but the user can change while the merge runs. So the target uid is passed to the publisher,
 *   which re-checks it at the moment of the call and refuses otherwise (as the uploader does).
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
        private val ghostScores: GhostScorePublisher,
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
            // After every upload, before re-owning and clearing the entry: see "Ghost Duel best
            // score" in the class comment for why that placement makes a retry safe.
            publishBestScores(toMerge, targetUid)
            guestSessions.reassignOwner(toMerge.map { it.id }, guestUid, targetUid)
            markerStore.remove(guestUid)
            return Result.success(toMerge.size)
        }

        /**
         * Publishes the best session of each exercise in [sessions], one call per exercise.
         *
         * Best-effort by design: neither a failed [Result] nor an exception escapes, so the merge
         * carries on to re-owning and clearing the entry. Only cancellation is rethrown, because
         * swallowing it would keep a cancelled coroutine running. Each exercise is attempted on
         * its own, so one failure does not skip the others.
         *
         * [targetUid] goes to the publisher, which refuses unless that account is still the
         * signed-in one when it runs.
         */
        private suspend fun publishBestScores(
            sessions: List<WorkoutSession>,
            targetUid: String,
        ) {
            val bestPerExercise =
                sessions
                    .filter { it.reps.isNotEmpty() }
                    .groupBy { it.exercise }
                    .mapValues { (_, group) -> group.reduce { best, next -> if (next.isBetterThan(best)) next else best } }

            for (best in bestPerExercise.values) {
                try {
                    // The returned Result is deliberately not inspected: the publisher has already
                    // logged any failure, and a failure must not change what the merge does next.
                    ghostScores.publish(best, targetUid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safety net only: a well-behaved publisher returns a failed Result and logs it
                    // itself. Not logged here so this class never touches android.util.Log, which
                    // would throw in the JVM tests; the merge must go on either way.
                }
            }
        }

        /**
         * True when this session should replace [other] as an exercise's best score. Mirrors the
         * rule `FirestoreGhostScoreDataSource.publishBestScore` applies to the stored score
         * (higher average, then more reps, then newer), so the session we pick is the one the
         * store would keep anyway. Both sessions must have reps.
         */
        private fun WorkoutSession.isBetterThan(other: WorkoutSession): Boolean {
            val difference = averageScore() - other.averageScore()
            return when {
                difference > SCORE_EPSILON -> true
                difference < -SCORE_EPSILON -> false
                reps.size != other.reps.size -> reps.size > other.reps.size
                else -> startedAt > other.startedAt
            }
        }

        private fun WorkoutSession.averageScore(): Double = reps.map { it.score.toDouble() }.average()

    }
