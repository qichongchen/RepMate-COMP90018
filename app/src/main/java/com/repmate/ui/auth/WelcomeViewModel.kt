package com.repmate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** [WelcomeScreen]'s state: just the guest path's loading/error, since Sign up and Log in are plain navigation from here, not async. */
data class WelcomeUiState(
    val isGuestLoading: Boolean = false,
    val generalError: String? = null,
) {
    val isBusy: Boolean get() = isGuestLoading
}

/**
 * Backs [WelcomeScreen]'s "Continue as guest" action.
 *
 * `signInAnonymously()` creates a real Firebase user (an anonymous UID) -- this is not a
 * client-side stub. Firebase's SDK persists that session on-device by default, the same as any
 * other sign-in method: a guest who reopens the app later is still signed in as that same
 * anonymous user via `firebaseAuth.currentUser`, with no code needed here to save or restore
 * that -- and deliberately none here to clear or reset it either. If `signInAnonymously()` is
 * called again while that session is still current (e.g. tapping the button a second time), the
 * SDK resolves it against the existing session rather than minting a new UID.
 *
 * Anonymous auth has none of email/password's failure modes (no collision, no weak-password, no
 * invalid-credential) -- a network failure is the only realistic way this call fails.
 */
@HiltViewModel
class WelcomeViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WelcomeUiState())
        val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

        private val _guestSignInSucceeded = Channel<Unit>(Channel.BUFFERED)

        /** Fires once per successful anonymous sign-in. A [Channel], not a field on [uiState] -- see [AuthViewModel.authSucceeded] for why. */
        val guestSignInSucceeded = _guestSignInSucceeded.receiveAsFlow()

        fun onContinueAsGuestClicked() {
            _uiState.update { it.copy(isGuestLoading = true, generalError = null) }
            viewModelScope.launch {
                try {
                    firebaseAuth.signInAnonymously().await()
                    _uiState.update { it.copy(isGuestLoading = false) }
                    _guestSignInSucceeded.send(Unit)
                } catch (e: FirebaseNetworkException) {
                    _uiState.update {
                        it.copy(isGuestLoading = false, generalError = "No internet connection. Check your network and try again.")
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isGuestLoading = false, generalError = "Something went wrong. Please try again.") }
                }
            }
        }
    }
