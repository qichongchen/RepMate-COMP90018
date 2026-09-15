package com.repmate.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.example.repmate.BuildConfig
import com.repmate.ui.components.LoadingOverlay
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme
import kotlinx.coroutines.launch

/**
 * The sign-up screen: brand-agnostic form (Google + email/password), wired to [AuthViewModel].
 *
 * Split into this stateful wrapper and the stateless [SignUpContent] below so previews (and any
 * future UI test) can render the form directly from a plain [AuthFormUiState], without needing
 * Hilt or a real [CredentialManager] available -- neither of which `@Preview` can provide.
 *
 * @param onBackClick invoked when the back chevron is tapped.
 * @param onSignUpSuccess invoked once, after either auth path succeeds.
 * @param onLogInClick invoked when the "Log in" link at the bottom is tapped.
 */
@Composable
fun SignUpScreen(
    onBackClick: () -> Unit,
    onSignUpSuccess: () -> Unit,
    onLogInClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.authSucceeded.collect { onSignUpSuccess() }
    }

    SignUpContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onCreateAccountClick = viewModel::onCreateAccountClicked,
        // Firebase config confirmed working (Auth providers, Google Sign-In fingerprint, Hilt
        // binding) - tested by Lisa, 15 Sep.
        onGoogleClick = {
            coroutineScope.launch {
                viewModel.onGoogleSignInStarted()
                runCatching { requestGoogleIdToken(context, BuildConfig.GOOGLE_WEB_CLIENT_ID) }
                    .onSuccess { idToken -> viewModel.onGoogleIdTokenReceived(idToken) }
                    .onFailure { error ->
                        if (error is GetCredentialCancellationException) {
                            viewModel.onGoogleSignInCancelled()
                        } else {
                            viewModel.onGoogleSignInFailed(googleSignInErrorMessage(error))
                        }
                    }
            }
        },
        onLogInClick = onLogInClick,
        modifier = modifier,
    )
}

@Composable
private fun SignUpContent(
    uiState: AuthFormUiState,
    onBackClick: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCreateAccountClick: () -> Unit,
    onGoogleClick: () -> Unit,
    onLogInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Box, not Column, is the root: LoadingOverlay is a later sibling here so it draws on top of
    // the whole screen and dims/blocks it, rather than needing its own placement logic.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            // Everything except the bottom "Log in" link scrolls as one unit -- a full form plus
            // an open keyboard can easily overflow a short/small-width phone.
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(top = 16.dp, bottom = 24.dp),
            ) {
                AuthBackButton(onClick = onBackClick)

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Create account",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "track your reps across devices",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(32.dp))
                // No button-level spinner here or on "Create account" below -- the full-screen
                // LoadingOverlay is the one visual indicator for "a request is in flight" now,
                // so both buttons only need `enabled` to stop looking (and being) tappable.
                AuthGoogleButton(onClick = onGoogleClick, enabled = !uiState.isBusy)

                Spacer(modifier = Modifier.height(24.dp))
                AuthDivider()

                // Form-level errors (account collision, bad credentials, network) show right
                // here -- the first thing under the divider, above both fields -- so they don't
                // require scrolling past the form to notice. Field-level validation ("password
                // too short") stays as supportingText under its own field below; this is only
                // for errors that aren't about one specific field.
                if (uiState.generalError != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    AuthErrorBanner(uiState.generalError)
                }

                Spacer(modifier = Modifier.height(24.dp))
                AuthLabeledField(
                    label = "Email",
                    value = uiState.email,
                    onValueChange = onEmailChanged,
                    errorText = uiState.emailError,
                    enabled = !uiState.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    placeholder = "johndoe@example.com",
                )

                Spacer(modifier = Modifier.height(16.dp))
                AuthLabeledField(
                    label = "Password",
                    value = uiState.password,
                    onValueChange = onPasswordChanged,
                    errorText = uiState.passwordError,
                    enabled = !uiState.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // "Password" rather than a dot-mask string: a placeholder made of literal
                    // bullet characters risks reading as if the field already has masked input.
                    placeholder = "Password",
                    visualTransformation = PasswordVisualTransformation(),
                )
                // NOTE: no name field here on purpose -- name is collected later in Profile
                // setup, to keep this form to the two fields Firebase actually needs.

                Spacer(modifier = Modifier.height(24.dp))
                RepMateButton(
                    text = "Create account",
                    onClick = onCreateAccountClick,
                    enabled = !uiState.isBusy,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "By continuing you agree to RepMate's Terms & Privacy Policy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AuthBottomPrompt(
                promptText = "Already have an account? ",
                actionText = "Log in",
                onActionClick = onLogInClick,
            )
        }

        // uiState.isBusy covers both auth paths: set the moment either one starts (email/
        // password's own call, or Google's -- from the instant the Credential Manager picker is
        // launched, all the way through the Firebase credential exchange) and cleared only once
        // that path resolves, success or failure. See AuthViewModel.onGoogleSignInStarted/
        // onGoogleIdTokenReceived for exactly where those two boundaries are.
        if (uiState.isBusy) {
            LoadingOverlay()
        }
    }
}

private val PREVIEW_ERROR_STATE =
    AuthFormUiState(
        email = "jess@example.com",
        passwordError = "Password must be at least 6 characters.",
        generalError = "An account with this email already exists.",
    )

@Preview(name = "Light - Default", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        SignUpContent(
            uiState = AuthFormUiState(email = "jess@example.com"),
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onCreateAccountClick = {},
            onGoogleClick = {},
            onLogInClick = {},
        )
    }
}

@Preview(name = "Dark - Default", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        SignUpContent(
            uiState = AuthFormUiState(email = "jess@example.com"),
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onCreateAccountClick = {},
            onGoogleClick = {},
            onLogInClick = {},
        )
    }
}

@Preview(name = "Light - Error state", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenLightErrorPreview() {
    RepMateTheme(darkTheme = false) {
        SignUpContent(
            uiState = PREVIEW_ERROR_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onCreateAccountClick = {},
            onGoogleClick = {},
            onLogInClick = {},
        )
    }
}

@Preview(name = "Dark - Error state", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenDarkErrorPreview() {
    RepMateTheme(darkTheme = true) {
        SignUpContent(
            uiState = PREVIEW_ERROR_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onCreateAccountClick = {},
            onGoogleClick = {},
            onLogInClick = {},
        )
    }
}

private val PREVIEW_LOADING_STATE = AuthFormUiState(email = "jess@example.com", isEmailLoading = true)

@Preview(name = "Light - Loading overlay", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenLightLoadingPreview() {
    RepMateTheme(darkTheme = false) {
        SignUpContent(
            uiState = PREVIEW_LOADING_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onCreateAccountClick = {},
            onGoogleClick = {},
            onLogInClick = {},
        )
    }
}

@Preview(name = "Dark - Loading overlay", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenDarkLoadingPreview() {
    RepMateTheme(darkTheme = true) {
        SignUpContent(
            uiState = PREVIEW_LOADING_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onCreateAccountClick = {},
            onGoogleClick = {},
            onLogInClick = {},
        )
    }
}
