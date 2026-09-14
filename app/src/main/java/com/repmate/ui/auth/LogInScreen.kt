package com.repmate.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.repmate.BuildConfig
import com.repmate.ui.components.LoadingOverlay
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme
import kotlinx.coroutines.launch

/**
 * The log-in screen: brand-agnostic form (Google + email/password), wired to the same
 * [AuthViewModel] class [SignUpScreen] uses -- see that ViewModel's class doc for why that's a
 * shared class, not a shared live instance.
 *
 * Split into this stateful wrapper and the stateless [LogInContent] below, same reasoning as
 * [SignUpScreen]/`SignUpContent`: previews render the form from a plain [AuthFormUiState],
 * without needing Hilt or a real [CredentialManager], neither of which `@Preview` can provide.
 *
 * @param onBackClick invoked when the back chevron is tapped.
 * @param onLogInSuccess invoked once, after either auth path succeeds.
 * @param onForgotPasswordClick invoked when "Forgot password?" is tapped.
 * @param onSignUpClick invoked when the "Sign up" link at the bottom is tapped.
 */
@Composable
fun LogInScreen(
    onBackClick: () -> Unit,
    onLogInSuccess: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onSignUpClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.authSucceeded.collect { onLogInSuccess() }
    }

    LogInContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onLogInClick = viewModel::onLogInClicked,
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
        onForgotPasswordClick = onForgotPasswordClick,
        onSignUpClick = onSignUpClick,
        modifier = modifier,
    )
}

@Composable
private fun LogInContent(
    uiState: AuthFormUiState,
    onBackClick: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogInClick: () -> Unit,
    onGoogleClick: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onSignUpClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same structure as SignUpContent: Box root so LoadingOverlay draws on top of everything as
    // a later sibling, Column below scrolls except for the pinned bottom prompt.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
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
                    text = "Welcome back",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "log in to continue",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(32.dp))
                AuthGoogleButton(onClick = onGoogleClick, enabled = !uiState.isBusy)

                Spacer(modifier = Modifier.height(24.dp))
                AuthDivider()

                // Same placement as SignUp: form-level errors ("incorrect email or password",
                // network failures) sit right under the divider, above both fields, so they
                // don't require scrolling past the form to notice.
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
                    placeholder = "jess@example.com",
                )

                Spacer(modifier = Modifier.height(16.dp))
                AuthLabeledField(
                    label = "Password",
                    value = uiState.password,
                    onValueChange = onPasswordChanged,
                    errorText = uiState.passwordError,
                    enabled = !uiState.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    placeholder = "Password",
                    visualTransformation = PasswordVisualTransformation(),
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "Forgot password?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onForgotPasswordClick),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                RepMateButton(
                    text = "Log in",
                    onClick = onLogInClick,
                    enabled = !uiState.isBusy,
                )
            }

            AuthBottomPrompt(
                promptText = "Don't have an account? ",
                actionText = "Sign up",
                onActionClick = onSignUpClick,
            )
        }

        // Same isBusy contract as SignUpScreen: true from the moment either auth path starts
        // until it resolves (success or failure) -- see AuthViewModel.onLogInClicked and
        // onGoogleSignInStarted/onGoogleIdTokenReceived for exactly where those boundaries are.
        if (uiState.isBusy) {
            LoadingOverlay()
        }
    }
}

private val PREVIEW_ERROR_STATE =
    AuthFormUiState(
        email = "jess@example.com",
        generalError = "Incorrect email or password.",
    )

@Preview(name = "Light - Default", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LogInScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        LogInContent(
            uiState = AuthFormUiState(email = "jess@example.com"),
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onLogInClick = {},
            onGoogleClick = {},
            onForgotPasswordClick = {},
            onSignUpClick = {},
        )
    }
}

@Preview(name = "Dark - Default", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LogInScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LogInContent(
            uiState = AuthFormUiState(email = "jess@example.com"),
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onLogInClick = {},
            onGoogleClick = {},
            onForgotPasswordClick = {},
            onSignUpClick = {},
        )
    }
}

@Preview(name = "Light - Error state", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LogInScreenLightErrorPreview() {
    RepMateTheme(darkTheme = false) {
        LogInContent(
            uiState = PREVIEW_ERROR_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onLogInClick = {},
            onGoogleClick = {},
            onForgotPasswordClick = {},
            onSignUpClick = {},
        )
    }
}

@Preview(name = "Dark - Error state", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun LogInScreenDarkErrorPreview() {
    RepMateTheme(darkTheme = true) {
        LogInContent(
            uiState = PREVIEW_ERROR_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onLogInClick = {},
            onGoogleClick = {},
            onForgotPasswordClick = {},
            onSignUpClick = {},
        )
    }
}
