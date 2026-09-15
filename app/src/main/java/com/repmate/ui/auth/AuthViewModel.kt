package com.repmate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
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
 * Email/password + Google auth form state, shared by [SignUpScreen] and [LogInScreen]: the two
 * fields, their inline validation messages, which auth path is currently in flight, and any
 * error to show inline. Both screens use this exact shape (that's why it isn't `SignUpUiState`),
 * even though what happens on submit differs -- see [AuthViewModel.onCreateAccountClicked] vs
 * [AuthViewModel.onLogInClicked].
 */
data class AuthFormUiState(
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
 * Backs both [SignUpScreen] and [LogInScreen]'s form state and their two auth paths
 * (email/password and Google). Each screen gets its own instance (Hilt scopes a `hiltViewModel()`
 * to the current nav back stack entry), so there's no live state actually shared between them at
 * once -- what's shared is the class: identical validation, loading/error handling, and the
 * Google exchange, so neither screen risks drifting from the other's already-fixed behaviour.
 *
 * Firebase calls happen here, not in the Composable, so each screen stays a plain renderer of
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
        private val _uiState = MutableStateFlow(AuthFormUiState())
        val uiState: StateFlow<AuthFormUiState> = _uiState.asStateFlow()

        private val _authSucceeded = Channel<Unit>(Channel.BUFFERED)

        /**
         * Fires once per successful sign-up or log-in (email or Google). A [Channel], not a
         * field on [uiState] -- a plain boolean flag would re-fire navigation on every
         * recomposition/process restore that still saw `true`, where a Channel is drained the
         * moment it's collected. Deliberately doesn't say *where* to navigate: that's the
         * screen's call (`onSignUpSuccess` goes to onboarding, `onLogInSuccess` goes to home),
         * not this ViewModel's.
         */
        val authSucceeded = _authSucceeded.receiveAsFlow()

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
            val emailError = validateEmailFormat(state.email)
            val passwordError = validateNewPassword(state.password)
            if (emailError != null || passwordError != null) {
                _uiState.update { it.copy(emailError = emailError, passwordError = passwordError) }
                return
            }

            _uiState.update { it.copy(isEmailLoading = true, generalError = null) }
            viewModelScope.launch {
                try {
                    firebaseAuth.createUserWithEmailAndPassword(state.email, state.password).await()
                    _uiState.update { it.copy(isEmailLoading = false) }
                    _authSucceeded.send(Unit)
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
         * Validates client-side first, then calls `signInWithEmailAndPassword`. Only checks that
         * both fields are non-empty and the email looks well-formed -- unlike sign-up, there's no
         * minimum-length check here: this is someone's existing password, whatever it already is.
         */
        fun onLogInClicked() {
            val state = _uiState.value
            val emailError = validateEmailFormat(state.email)
            val passwordError = validateExistingPassword(state.password)
            if (emailError != null || passwordError != null) {
                _uiState.update { it.copy(emailError = emailError, passwordError = passwordError) }
                return
            }

            _uiState.update { it.copy(isEmailLoading = true, generalError = null) }
            viewModelScope.launch {
                try {
                    firebaseAuth.signInWithEmailAndPassword(state.email, state.password).await()
                    _uiState.update { it.copy(isEmailLoading = false) }
                    _authSucceeded.send(Unit)
                } catch (e: FirebaseAuthInvalidUserException) {
                    // Deliberately the same message as a wrong password: don't reveal whether an
                    // email is registered at all.
                    failEmail("Incorrect email or password.")
                } catch (e: FirebaseAuthInvalidCredentialsException) {
                    failEmail("Incorrect email or password.")
                } catch (e: FirebaseNetworkException) {
                    failEmail("No internet connection. Check your network and try again.")
                } catch (e: Exception) {
                    failEmail("Something went wrong signing in. Please try again.")
                }
            }
        }

        /**
         * Called by [SignUpScreen] or [LogInScreen] once Android's Credential Manager hands back
         * a Google ID token. The Credential Manager prompt itself has to run in the Composable --
         * see the class doc -- so this is the handoff point: from here on it's the same Firebase
         * exchange regardless of how the token was obtained, and regardless of which screen it
         * came from -- `signInWithCredential` transparently creates the account if this Google
         * identity is new, or logs in if it isn't, so sign-up and log-in need no separate paths.
         */
        // Firebase config confirmed working (Auth providers, Google Sign-In fingerprint, Hilt
        // binding) - tested by Lisa, 15 Sep.
        fun onGoogleIdTokenReceived(idToken: String) {
            viewModelScope.launch {
                try {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    firebaseAuth.signInWithCredential(credential).await()
                    _uiState.update { it.copy(isGoogleLoading = false) }
                    _authSucceeded.send(Unit)
                } catch (e: FirebaseNetworkException) {
                    failGoogle("No internet connection. Check your network and try again.")
                } catch (e: Exception) {
                    failGoogle("Google sign-in failed. Please try again.")
                }
            }
        }

        /** Credential Manager's picker is about to show; reflected as loading immediately, before any token comes back. */
        fun onGoogleSignInStarted() {
            _uiState.update { it.copy(isGoogleLoading = true, generalError = null) }
        }

        /** Called when Credential Manager itself fails (not a user cancel -- that's [onGoogleSignInCancelled]). */
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

        private fun validateNewPassword(password: String): String? =
            when {
                password.isBlank() -> "Enter a password."
                password.length < MIN_PASSWORD_LENGTH -> "Password must be at least $MIN_PASSWORD_LENGTH characters."
                else -> null
            }

        private fun validateExistingPassword(password: String): String? =
            if (password.isBlank()) "Enter your password." else null
    }
