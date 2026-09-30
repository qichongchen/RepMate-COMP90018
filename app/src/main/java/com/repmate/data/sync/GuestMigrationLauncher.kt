package com.repmate.data.sync

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.repmate.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android-facing entry point to [GuestHistoryMigrator]: what the auth screens and the
 * Application call. It owns the two things the pure migrator must not: the process-lifetime
 * coroutine scope (so a merge keeps running after the user leaves the sign-in screen and its
 * ViewModel is cleared) and the wait for Firebase to restore the signed-in user at launch.
 *
 * Nothing here may break sign-in (Golden Rule 7): a failure to write or run the merge is logged
 * and swallowed, and the marker stays for the next launch.
 */
@Singleton
class GuestMigrationLauncher
    @Inject
    constructor(
        private val migrator: GuestHistoryMigrator,
        private val firebaseAuth: FirebaseAuth,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /** Records [guestUid] before a sign-in that will replace the guest. */
        suspend fun markGuestPending(guestUid: String) {
            Log.d(TAG, "Sign-in will replace guest $guestUid; marking merge pending")
            safely("mark pending") { migrator.markPending(guestUid) }
        }

        /** The replacing sign-in failed or was cancelled: forget the guest UID captured for it. */
        suspend fun discardUnresolvedCapture() = safely("discard capture") { migrator.discardUnresolvedCapture() }

        /** The replacing sign-in succeeded: note the target account, then merge in the background. */
        suspend fun onSignedIn() {
            Log.d(TAG, "Sign-in succeeded; recording target and starting merge")
            safely("record sign-in") { migrator.recordSignIn() }
            launchResume()
        }

        /** Picks up a merge left unfinished by a crash, kill or lost connection. Call once at app start. */
        fun resumeOnAppStart() {
            scope.launch {
                // Firebase restores the persisted user asynchronously; wait briefly for it instead
                // of reading a possibly-still-null currentUser and skipping the resume.
                val uid = withTimeoutOrNull(AUTH_WAIT_MS) { firebaseAuth.uidUpdates().first { it != null } }
                if (uid != null) runResume()
            }
        }

        private fun launchResume() {
            scope.launch { runResume() }
        }

        private suspend fun runResume() {
            migrator
                .resume()
                // NOTE: logged even when 0, so "nothing to do" is distinguishable from "never ran".
                .onSuccess { merged -> Log.d(TAG, "Merge finished: $merged guest sessions moved (0 = nothing pending, or the guest had no sessions)") }
                .onFailure { error -> Log.w(TAG, "Guest merge did not finish; will retry on next launch", error) }
        }

        private suspend fun safely(
            what: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Guest merge: failed to $what", e)
            }
        }

        private fun FirebaseAuth.uidUpdates(): Flow<String?> =
            callbackFlow {
                val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser?.uid) }
                addAuthStateListener(listener)
                awaitClose { removeAuthStateListener(listener) }
            }

        private companion object {
            const val TAG = "GuestMigration"
            const val AUTH_WAIT_MS = 10_000L
        }
    }
