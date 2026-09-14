package com.repmate.ui.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
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

/** Minimum password length Firebase's email/password auth enforces server-side. */
private const val MIN_PASSWORD_LENGTH = 6

/**
 * Sign-up form state: the two fields, their inline validation messages, which auth path (email
 * or Google) is currently in flight, and any error to show inline.
 */
data class SignUpUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val isEmailLoading: Boolean = false,
    val isGoogleLoading: Boolean = false,
    val generalError: String? = null,
) {
    /** True while either auth path is in flight -- both buttons disable together so a user can't fire both at once. */
    val isBusy: Boolean get() = isEmailLoading || isGoogleLoading
}

/**
 * Backs [SignUpScreen]'s form state and both its auth paths (email/password and Google).
 *
 * NOTE: named generically, not `SignUpViewModel`, because LogInScreen (not built yet) will need
 * the same email/password/loading/error shape. If its needs diverge once it exists, split this
 * into separate ViewModels then -- no point guessing the split now.
 *
 * Firebase calls happen here, not in the Composable, so the screen stays a plain renderer of
 * [uiState]. The one thing that can't live here is the Google Credential Manager prompt itself:
 * it needs an Activity [android.content.Context], which a ViewModel must never hold a reference
 * to (leaks the Activity past its lifecycle) -- see [onGoogleIdTokenReceived].
 */
@HiltViewModel
class AuthViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SignUpUiState())
        val uiState: StateFlow<SignUpUiState> = _uiState.asStateFlow()

        private val _navigateToOnboarding = Channel<Unit>(Channel.BUFFERED)

        /**
         * Fires once per successful sign-up. A [Channel], not a field on [uiState] -- a plain
         * boolean flag would re-fire navigation on every recomposition/process restore that
         * still saw `true`, where a Channel is drained the moment it's collected.
         */
        val navigateToOnboarding = _navigateToOnboarding.receiveAsFlow()

        fun onEmailChanged(value: String) {
            _uiState.update { it.copy(email = value, emailError = null, generalError = null) }
        }

        fun onPasswordChanged(value: String) {
            _uiState.update { it.copy(password = value, passwordError = null, generalError = null) }
        }

        /**
         * Validates client-side first (Golden Rule: don't rely on the Firebase round-trip for
         * obvious cases), then calls `createUserWithEmailAndPassword`.
         */
        fun onCreateAccountClicked() {
            val state = _uiState.value
            val emailError = validateEmail(state.email)
            val passwordError = validatePassword(state.password)
            if (emailError != null || passwordError != null) {
                _uiState.update { it.copy(emailError = emailError, passwordError = passwordError) }
                return
            }

            _uiState.update { it.copy(isEmailLoading = true, generalError = null) }
            viewModelScope.launch {
                try {
                    firebaseAuth.createUserWithEmailAndPassword(state.email, state.password).await()
                    _uiState.update { it.copy(isEmailLoading = false) }
                    _navigateToOnboarding.send(Unit)
                } catch (e: FirebaseAuthUserCollisionException) {
                    failEmail("An account with this email already exists.")
                } catch (e: FirebaseAuthWeakPasswordException) {
                    failEmail(e.reason ?: "That password is too weak.")
                } catch (e: FirebaseAuthInvalidCredentialsException) {
                    failEmail("That email address looks invalid.")
                } catch (e: FirebaseNetworkException) {
                    failEmail("No internet connection. Check your network and try again.")
                } catch (e: Exception) {
                    failEmail("Something went wrong creating your account. Please try again.")
                }
            }
        }

        /**
         * Called by [SignUpScreen] once Android's Credential Manager hands back a Google ID
         * token. The Credential Manager prompt itself has to run in the Composable -- see the
         * class doc -- so this is the handoff point: from here on it's the same Firebase
         * exchange regardless of how the token was obtained.
         */
        fun onGoogleIdTokenReceived(idToken: String) {
            viewModelScope.launch {
                try {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    firebaseAuth.signInWithCredential(credential).await()
                    _uiState.update { it.copy(isGoogleLoading = false) }
                    _navigateToOnboarding.send(Unit)
                } catch (e: FirebaseNetworkException) {
                    failGoogle("No internet connection. Check your network and try again.")
                } catch (e: Exception) {
                    failGoogle("Google sign-up failed. Please try again.")
                }
            }
        }

        /** Credential Manager's picker is about to show; reflected as loading immediately, before any token comes back. */
        fun onGoogleSignInStarted() {
            _uiState.update { it.copy(isGoogleLoading = true, generalError = null) }
        }

        /** Called by [SignUpScreen] when Credential Manager itself fails (not a user cancel -- that's [onGoogleSignInCancelled]). */
        fun onGoogleSignInFailed(message: String) {
            failGoogle(message)
        }

        /** The user dismissed the Google account picker -- not an error, just back to idle. */
        fun onGoogleSignInCancelled() {
            _uiState.update { it.copy(isGoogleLoading = false) }
        }

        fun dismissError() {
            _uiState.update { it.copy(generalError = null) }
        }

        private fun failEmail(message: String) {
            _uiState.update { it.copy(isEmailLoading = false, generalError = message) }
        }

        private fun failGoogle(message: String) {
            _uiState.update { it.copy(isGoogleLoading = false, generalError = message) }
        }

        private fun validateEmail(email: String): String? =
            when {
                email.isBlank() -> "Enter your email."
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address."
                else -> null
            }

        private fun validatePassword(password: String): String? =
            when {
                password.isBlank() -> "Enter a password."
                password.length < MIN_PASSWORD_LENGTH -> "Password must be at least $MIN_PASSWORD_LENGTH characters."
                else -> null
            }
    }
