package com.repmate.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.repmate.data.repo.ClaimStatus
import com.repmate.data.repo.UsernameRepository
import com.repmate.data.repo.needsDisplayNameGate
import com.repmate.ui.onboarding.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Decides which screen the app opens on, so a user whose Firebase session was restored from disk
 * (email or guest) goes straight to Home instead of seeing Welcome on every launch.
 *
 * [startDestination] is null while resolving. `MainActivity` keeps the system splash screen up for
 * exactly that long and only composes `RepMateNavGraph` once it is non-null, so the first frame
 * the user sees is already the right screen.
 */
@HiltViewModel
class StartupViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val onboardingPreferences: OnboardingPreferences,
        private val usernameRepository: UsernameRepository,
    ) : ViewModel() {
        private val _startDestination = MutableStateFlow<String?>(null)
        val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

        init {
            viewModelScope.launch {
                _startDestination.value =
                    resolveStartDestination(
                        firstAuthState = firebaseAuth.authStateUids(),
                        currentUid = { firebaseAuth.currentUser?.uid },
                        hasSeenOnboarding = onboardingPreferences::hasCompletedOnboarding,
                        isAnonymous = { firebaseAuth.currentUser?.isAnonymous == true },
                        claimStatus = { usernameRepository.claimStatus() },
                    )
            }
        }
    }

/** How long to wait for Firebase's first auth state before falling back to `currentUser`. */
internal const val AUTH_STATE_TIMEOUT_MS = 3_000L

/**
 * The uid of the signed-in user (null when signed out), emitted each time Firebase's auth state
 * changes. Firebase calls a newly added listener straight away with the state it has, which is what
 * makes `.first()` on this flow work as "the first auth state".
 */
private fun FirebaseAuth.authStateUids(): Flow<String?> =
    callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser?.uid) }
        addAuthStateListener(listener)
        awaitClose { removeAuthStateListener(listener) }
    }

/** Wrapper so a legitimate "signed out" (null uid) is distinguishable from a timeout (null result). */
private class FirstAuthState(val uid: String?)

/**
 * Pure decision logic behind [StartupViewModel], with its dependencies passed in as functions so
 * it runs in a plain JVM test with no Firebase.
 *
 * Why wait for the first auth *event* instead of reading `currentUser`: Firebase restores the
 * persisted session asynchronously, so `currentUser` can be null for a moment on a cold start even
 * though the user is signed in, which would wrongly send them to Welcome. If Firebase does not
 * report within [timeoutMs] (e.g. no connectivity and a slow init), `currentUser` is the fallback,
 * so the app is never stuck on the splash.
 *
 * The signed-in destination comes from [RepMateDestinations.afterAuth], the same rule
 * `RepMateNavGraph` applies after a sign-in, so "onboarding or home" is decided in one place.
 *
 * @param firstAuthState emits the uid (or null) each time the auth state changes.
 * @param currentUid a direct read of the current user's uid, used only after a timeout.
 * @param hasSeenOnboarding whether that uid has completed onboarding (per-uid, see
 *   [OnboardingPreferences]).
 * @param isAnonymous whether the signed-in user is a guest; guests skip the display-name gate.
 * @param claimStatus whether the signed-in user has claimed a display name. Answers from a local
 *   cache when it can, else one short server read; [ClaimStatus.UNKNOWN] (offline, timeout) never
 *   gates the user, so being offline cannot trap anyone on the choose-name screen.
 */
internal suspend fun resolveStartDestination(
    firstAuthState: Flow<String?>,
    currentUid: () -> String?,
    hasSeenOnboarding: suspend (uid: String) -> Boolean,
    isAnonymous: () -> Boolean,
    claimStatus: suspend () -> ClaimStatus,
    timeoutMs: Long = AUTH_STATE_TIMEOUT_MS,
): String {
    val first = withTimeoutOrNull(timeoutMs) { FirstAuthState(firstAuthState.first()) }
    // first == null means the wait timed out; a signed-out user is first != null with a null uid.
    val uid = if (first != null) first.uid else currentUid()
    if (uid == null) return RepMateDestinations.WELCOME

    val seen =
        try {
            hasSeenOnboarding(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Degrade gracefully: a signed-in user must never be stuck on the splash because the
            // local preference read failed. Onboarding is skippable, so it is the safe default.
            false
        }

    val anonymous = isAnonymous()
    val status =
        if (anonymous) {
            ClaimStatus.UNKNOWN
        } else {
            try {
                claimStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same reasoning as the onboarding read: never strand a signed-in user on the splash.
                ClaimStatus.UNKNOWN
            }
        }
    return RepMateDestinations.afterAuth(
        hasSeenOnboarding = seen,
        needsDisplayName = needsDisplayNameGate(isAnonymous = anonymous, status = status),
    )
}
