package com.repmate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** How long a resend is blocked after sending, to avoid hammering Firebase's rate limit. */
private const val RESEND_COOLDOWN_SECONDS = 60

/**
 * State for both stages of [ForgotPasswordScreen] -- request ([isSent] false) and confirmation
 * ([isSent] true) -- since it's one screen with internal state, not two destinations.
 */
data class ForgotPasswordUiState(
    val email: String = "",
    val emailError: String? = null,
    val generalError: String? = null,
    val isLoading: Boolean = false,
    val isSent: Boolean = false,
    val resendCooldownSeconds: Int = 0,
) {
    /** False while a request is in flight or the post-send cooldown hasn't elapsed yet. */
    val canResend: Boolean get() = !isLoading && resendCooldownSeconds <= 0
}

/**
 * Backs [ForgotPasswordScreen]. Not [AuthViewModel]: this screen's state genuinely diverges --
 * no password field, no Google path, but a resend-cooldown timer neither SignUp nor LogIn need --
 * so per that class's own note, this is the split rather than bolting a timer onto it.
 *
 * The one Firebase call here, `sendPasswordResetEmail`, is deliberately treated as always
 * succeeding from the UI's perspective (see [sendResetEmail]): Firebase's email-enumeration
 * protection means this call resolves the same way whether or not the address is registered, on
 * purpose, so this screen must never let a different code path leak that distinction back to the
 * user. The only real error state is a network failure, which happens before any account lookup
 * and so is safe to surface.
 *
 * The resend cooldown's authoritative state is [cooldownTracker], not a field owned by this
 * class -- see that class's doc for why a ViewModel-local timer isn't enough to survive
 * navigating away from this screen and back.
 */
@HiltViewModel
class ForgotPasswordViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val cooldownTracker: PasswordResetCooldownTracker,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(initialState())
        val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

        private var cooldownJob: Job? = null

        init {
            // Restoring into an active cooldown (this screen was left and reopened while one was
            // still running) means the ticker needs to be running from the start too, not only
            // after the next onSendClicked/onResendClicked.
            if (_uiState.value.resendCooldownSeconds > 0) {
                startCooldownTicker()
            }
        }

        /**
         * If a cooldown from a previous visit to this screen is still active, restore straight
         * into the confirmation stage for the email it was sent to -- accurately reflecting "we
         * already sent you one" rather than showing a blank request form that implies otherwise.
         */
        private fun initialState(): ForgotPasswordUiState {
            val remaining = cooldownTracker.remainingSeconds(RESEND_COOLDOWN_SECONDS)
            val lastSentEmail = cooldownTracker.lastSentEmail
            return if (remaining > 0 && lastSentEmail != null) {
                ForgotPasswordUiState(email = lastSentEmail, isSent = true, resendCooldownSeconds = remaining)
            } else {
                ForgotPasswordUiState()
            }
        }

        /** Called once with whatever email the user had already typed on LogInScreen, if any. */
        fun onInitialEmail(email: String) {
            // The isBlank() check also covers "don't overwrite an email already restored by
            // initialState() from an active cooldown" -- that's never blank when it's set.
            if (email.isNotBlank() && _uiState.value.email.isBlank()) {
                _uiState.update { it.copy(email = email) }
            }
        }

        fun onEmailChanged(value: String) {
            _uiState.update { it.copy(email = value, emailError = null, generalError = null) }
        }

        fun onSendClicked() {
            val emailError = validateEmailFormat(_uiState.value.email)
            if (emailError != null) {
                _uiState.update { it.copy(emailError = emailError) }
                return
            }
            // Shouldn't normally be reachable -- an active cooldown puts the screen straight into
            // the sent state on init, and that state doesn't render this button -- but guarding
            // here too means a second request can't slip through some future UI change.
            if (cooldownTracker.remainingSeconds(RESEND_COOLDOWN_SECONDS) > 0) return
            sendResetEmail(_uiState.value.email)
        }

        fun onResendClicked() {
            if (!_uiState.value.canResend) return
            sendResetEmail(_uiState.value.email)
        }

        private fun sendResetEmail(email: String) {
            _uiState.update { it.copy(isLoading = true, generalError = null) }
            viewModelScope.launch {
                try {
                    firebaseAuth.sendPasswordResetEmail(email).await()
                } catch (e: FirebaseNetworkException) {
                    // A genuine network failure happens before Firebase ever looks the account
                    // up, so surfacing it doesn't leak anything -- unlike every other exception
                    // this call can throw, which is exactly what's deliberately not caught below.
                    _uiState.update {
                        it.copy(isLoading = false, generalError = "No internet connection. Check your network and try again.")
                    }
                    return@launch
                } catch (e: Exception) {
                    // Deliberately swallowed: anything else (including an invalid-user exception
                    // if the Firebase project doesn't have email-enumeration protection enabled)
                    // must not produce a different outcome than success, or this screen would be
                    // the leak the enumeration protection exists to prevent.
                }
                cooldownTracker.recordSent(email)
                _uiState.update { it.copy(isLoading = false, isSent = true, email = email) }
                startCooldownTicker()
            }
        }

        /**
         * Ticks once a second, but recomputes the remaining time from [cooldownTracker]'s
         * timestamp on every tick rather than just decrementing a local counter -- self-correcting
         * against `delay(1000)` drift and against the app having been backgrounded for a while.
         */
        private fun startCooldownTicker() {
            cooldownJob?.cancel()
            cooldownJob =
                viewModelScope.launch {
                    while (true) {
                        val remaining = cooldownTracker.remainingSeconds(RESEND_COOLDOWN_SECONDS)
                        _uiState.update { it.copy(resendCooldownSeconds = remaining) }
                        if (remaining <= 0) break
                        delay(1000)
                    }
                }
        }

        override fun onCleared() {
            cooldownJob?.cancel()
        }
    }
