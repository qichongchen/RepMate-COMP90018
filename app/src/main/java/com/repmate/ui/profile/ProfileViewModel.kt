package com.repmate.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.WorkoutSession
import com.repmate.safety.CheckInScheduler
import com.repmate.safety.SafetyCheckInPreferences
import com.repmate.safety.SafetyContact
import com.repmate.ui.auth.accountDisplayFor
import com.repmate.ui.theme.ThemePreferences
import com.repmate.ui.theme.WorkoutPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything [ProfileScreen] renders: the header (real, derived from [FirebaseAuth], including
 * [ProfileUiState.isGuest]), the dark
 * theme toggle (real, backed by [ThemePreferences]), the haptic feedback and spoken rep count
 * toggles (real, backed by [WorkoutPreferences]), [ProfileUiState.sessionsCount],
 * [ProfileUiState.totalReps] and [ProfileUiState.averageScore] (real, derived from
 * [SessionRepository.recent]), and the "Friends" row, which is a static placeholder for this
 * first pass.
 */
data class ProfileUiState(
    val name: String = "",
    /** Null means "show the generic person-silhouette fallback" -- see [com.repmate.ui.auth.accountDisplayFor]. */
    val avatarInitial: String? = null,
    val caption: String = "",
    /**
     * True for an anonymous (guest) session. Profile hides "Sign out" for a guest -- signing out
     * of an anonymous account discards it for good -- and offers "Create account" instead, which
     * upgrades the same account in place so the guest's history is kept.
     */
    val isGuest: Boolean = false,
    // Real, from SessionRepository -- see the ProfileViewModel init block. Both start at 0, the
    // correct value for a brand-new user with no sessions yet, and update the moment a session
    // is saved.
    val sessionsCount: Int = 0,
    val totalReps: Int = 0,
    /**
     * Real, derived from [SessionRepository.recent] like the two counts above: the mean score of
     * every rep across all sessions (see [averageRepScore]). 0 with no reps -- the screen shows
     * a dash then, not "0.0".
     */
    val averageScore: Float = 0f,
    /** Real -- mirrors [WorkoutPreferences.isHapticFeedbackEnabled]. */
    val hapticFeedbackEnabled: Boolean = true,
    /** Real -- mirrors [WorkoutPreferences.isSpokenRepCountEnabled]. */
    val spokenRepCountEnabled: Boolean = false,
    /** Real -- mirrors [ThemePreferences.isDarkThemeEnabled]. */
    val darkThemeEnabled: Boolean = true,
    /** Real -- mirrors [SafetyCheckInPreferences.isEnabled]. Turning this on from [ProfileScreen]
     * goes through a disclaimer and a permission request first; see its KDoc. */
    val safetyCheckInEnabled: Boolean = false,
    /** Real -- mirror [SafetyCheckInPreferences.contact]'s two halves. Both empty means no
     * contact has been saved yet. */
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
)

/**
 * Backs [ProfileScreen]. The header, sign-out, the dark theme, haptic feedback and spoken rep
 * count toggles, and the three session stats (sessions, total reps, average score) are real;
 * the "Friends" row is the only remaining placeholder, per the explicit scoping for this
 * screen's first pass.
 */
@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val themePreferences: ThemePreferences,
        private val workoutPreferences: WorkoutPreferences,
        private val sessionRepository: SessionRepository,
        private val safetyCheckInPreferences: SafetyCheckInPreferences,
        private val checkInScheduler: CheckInScheduler,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(buildInitialUiState(firebaseAuth))
        val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

        private val _signedOut = Channel<Unit>(Channel.BUFFERED)

        /** Fires once sign-out completes. A [Channel], not a field on [uiState] -- see [com.repmate.ui.auth.AuthViewModel.authSucceeded] for why. */
        val signedOut = _signedOut.receiveAsFlow()

        init {
            // Collected into uiState rather than read once: DataStore is the source of truth, and
            // MainActivity's own collection of the same flow is what actually re-themes the app --
            // this keeps the toggle's displayed position in sync with that, including if it's ever
            // changed from somewhere other than this screen.
            viewModelScope.launch {
                themePreferences.isDarkThemeEnabled.collect { enabled ->
                    _uiState.update { it.copy(darkThemeEnabled = enabled) }
                }
            }

            // Same reasoning as the dark theme toggle above: DataStore is the source of truth, and
            // the workout screens read the same flows to decide whether to buzz/speak per rep.
            viewModelScope.launch {
                workoutPreferences.isHapticFeedbackEnabled.collect { enabled ->
                    _uiState.update { it.copy(hapticFeedbackEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                workoutPreferences.isSpokenRepCountEnabled.collect { enabled ->
                    _uiState.update { it.copy(spokenRepCountEnabled = enabled) }
                }
            }

            // totalReps needs every session's rep list, not just a row count, so there is no
            // narrower query to ask SessionRepository for -- this has to walk the full history.
            // MAX_SESSIONS_FOR_STATS caps that at a size no real user will hit rather than asking
            // for a literally unbounded query.
            viewModelScope.launch {
                sessionRepository.recent(limit = MAX_SESSIONS_FOR_STATS).collect { sessions ->
                    _uiState.update {
                        it.copy(
                            sessionsCount = sessions.size,
                            totalReps = sessions.sumOf { session -> session.reps.size },
                            averageScore = averageRepScore(sessions),
                        )
                    }
                }
            }

            viewModelScope.launch {
                safetyCheckInPreferences.isEnabled.collect { enabled ->
                    _uiState.update { it.copy(safetyCheckInEnabled = enabled) }
                }
            }

            viewModelScope.launch {
                safetyCheckInPreferences.contact.collect { contact ->
                    _uiState.update {
                        it.copy(
                            emergencyContactName = contact?.name.orEmpty(),
                            emergencyContactPhone = contact?.phoneNumber.orEmpty(),
                        )
                    }
                }
            }
        }

        /**
         * `signOut()` is synchronous and local (it just clears the on-device session, no network
         * call), so unlike sign-in there's no loading state to show before firing [signedOut].
         */
        fun onSignOutClicked() {
            // Belt and braces: the screen hides the button for a guest, but signing out of an
            // anonymous account can never be undone, so refuse here too rather than trust the UI.
            if (firebaseAuth.currentUser?.isAnonymous == true) return
            firebaseAuth.signOut()
            _signedOut.trySend(Unit)
        }

        /**
         * Re-reads the account header (name, avatar, caption, guest flag). Called each time the
         * screen enters composition: upgrading a guest to a real account (see
         * `AuthViewModel.onCreateAccountClicked`) mutates the current user in place and does not
         * fire Firebase's auth-state listener, so without this the header would keep saying
         * "Guest session" after the user returns from Sign up.
         */
        fun refreshAccount() {
            val account = accountStateFor(firebaseAuth)
            _uiState.update {
                it.copy(
                    name = account.name,
                    avatarInitial = account.avatarInitial,
                    caption = account.caption,
                    isGuest = account.isGuest,
                )
            }
        }

        fun onDarkThemeToggled(enabled: Boolean) {
            viewModelScope.launch {
                themePreferences.setDarkThemeEnabled(enabled)
            }
        }

        fun onHapticFeedbackToggled(enabled: Boolean) {
            viewModelScope.launch { workoutPreferences.setHapticFeedbackEnabled(enabled) }
        }

        fun onSpokenRepCountToggled(enabled: Boolean) {
            viewModelScope.launch { workoutPreferences.setSpokenRepCountEnabled(enabled) }
        }

        /**
         * Called by [ProfileScreen] once it has already handled the disclaimer and the
         * permission request -- this just persists the result. [enabled] being true always means
         * SEND_SMS was granted; [ProfileScreen] never calls this with true otherwise.
         */
        fun onSafetyCheckInToggled(enabled: Boolean) {
            viewModelScope.launch {
                safetyCheckInPreferences.setEnabled(enabled)
                if (!enabled) checkInScheduler.disable()
            }
        }

        fun onEmergencyContactSaved(
            name: String,
            phoneNumber: String,
        ) {
            viewModelScope.launch {
                safetyCheckInPreferences.setContact(SafetyContact(name, phoneNumber))
            }
        }

        private companion object {
            /** Comfortably above any real user's lifetime session count, without querying unbounded. */
            const val MAX_SESSIONS_FOR_STATS = 10_000
        }
    }

/**
 * Mean rep score across every rep of every session in [sessions], or 0 when there are no reps.
 *
 * Pooled on purpose, not an average of per-session averages: a 1-rep session scoring 10 and a
 * 3-rep session scoring 6 pool to (10 + 6 * 3) / 4 = 7.0, whereas averaging the two session
 * averages would give 8.0 and let a single rep outweigh three. Pooling makes the figure "the
 * average rep", which is what the label promises.
 */
internal fun averageRepScore(sessions: List<WorkoutSession>): Float {
    val scores = sessions.flatMap { session -> session.reps }.map { rep -> rep.score }
    return if (scores.isEmpty()) 0f else scores.average().toFloat()
}

/** The header fields [ProfileUiState] takes from the signed-in user, in one place for init and [ProfileViewModel.refreshAccount]. */
private data class AccountState(
    val name: String,
    val avatarInitial: String?,
    val caption: String,
    val isGuest: Boolean,
)

private fun accountStateFor(firebaseAuth: FirebaseAuth): AccountState {
    val accountDisplay = accountDisplayFor(firebaseAuth)
    return AccountState(
        name = accountDisplay.name,
        avatarInitial = accountDisplay.avatarInitial,
        caption = accountDisplay.caption,
        isGuest = firebaseAuth.currentUser?.isAnonymous == true,
    )
}

private fun buildInitialUiState(firebaseAuth: FirebaseAuth): ProfileUiState {
    val account = accountStateFor(firebaseAuth)
    return ProfileUiState(
        name = account.name,
        avatarInitial = account.avatarInitial,
        caption = account.caption,
        isGuest = account.isGuest,
    )
}
