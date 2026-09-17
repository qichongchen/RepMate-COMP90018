package com.repmate.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.repmate.data.repo.SessionRepository
import com.repmate.ui.auth.accountDisplayFor
import com.repmate.ui.theme.ThemePreferences
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
 * Everything [ProfileScreen] renders: the header (real, derived from [FirebaseAuth]), the dark
 * theme toggle (real, backed by [ThemePreferences]), [ProfileUiState.sessionsCount] and
 * [ProfileUiState.totalReps] (real, derived from [SessionRepository.recent]), and the remaining
 * rows below, which are static placeholders for this first pass -- see the TODO on each
 * placeholder field/row for what it should read from once that data source exists.
 */
data class ProfileUiState(
    val name: String = "",
    /** Null means "show the generic person-silhouette fallback" -- see [com.repmate.ui.auth.accountDisplayFor]. */
    val avatarInitial: String? = null,
    val caption: String = "",
    // Real, from SessionRepository -- see the ProfileViewModel init block. Both start at 0, the
    // correct value for a brand-new user with no sessions yet, and update the moment a session
    // is saved.
    val sessionsCount: Int = 0,
    val totalReps: Int = 0,
    // TODO(data.local): still a fixed placeholder, unlike the two counts above.
    val averageScore: Float = 8.1f,
    // TODO(data.local / settings): both toggles are display-only placeholders (see ProfileScreen's
    // non-interactive Switches) until a real settings store exists to read/write them from.
    val safetyCheckInEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val spokenRepCountEnabled: Boolean = false,
    /** Real, unlike the toggles above -- mirrors [ThemePreferences.isDarkThemeEnabled]. */
    val darkThemeEnabled: Boolean = true,
)

/**
 * Backs [ProfileScreen]. The header, sign-out, the dark theme toggle, and the two session stats
 * are real; everything else in [ProfileUiState] is a fixed placeholder, per the explicit scoping
 * for this screen's first pass -- see the TODOs on [ProfileUiState] for what each one should
 * eventually read from.
 */
@HiltViewModel
class ProfileViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val themePreferences: ThemePreferences,
        private val sessionRepository: SessionRepository,
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
            firebaseAuth.signOut()
            _signedOut.trySend(Unit)
        }

        fun onDarkThemeToggled(enabled: Boolean) {
            viewModelScope.launch {
                themePreferences.setDarkThemeEnabled(enabled)
            }
        }

        private companion object {
            /** Comfortably above any real user's lifetime session count, without querying unbounded. */
            const val MAX_SESSIONS_FOR_STATS = 10_000
        }
    }

private fun buildInitialUiState(firebaseAuth: FirebaseAuth): ProfileUiState {
    val accountDisplay = accountDisplayFor(firebaseAuth)
    return ProfileUiState(
        name = accountDisplay.name,
        avatarInitial = accountDisplay.avatarInitial,
        caption = accountDisplay.caption,
    )
}
