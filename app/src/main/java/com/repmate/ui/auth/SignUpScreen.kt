package com.repmate.ui.auth

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.repmate.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.repmate.ui.components.LoadingOverlay
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme
import kotlinx.coroutines.launch

/**
 * The sign-up screen: brand-agnostic form (Google + email/password), wired to [AuthViewModel].
 *
 * Split into this stateful wrapper and the stateless [SignUpContent] below so previews (and any
 * future UI test) can render the form directly from a plain [SignUpUiState], without needing
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
        viewModel.navigateToOnboarding.collect { onSignUpSuccess() }
    }

    SignUpContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onCreateAccountClick = viewModel::onCreateAccountClicked,
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

/**
 * Launches Android's Credential Manager to request a Google ID token, per Google's current
 * recommended sign-in API (superseding the older `GoogleSignInClient`).
 *
 * @param webClientId the Firebase project's OAuth Web Client ID -- see the `GOOGLE_WEB_CLIENT_ID`
 *   comment in `app/build.gradle.kts` for where this has to come from.
 * @throws GetCredentialException if the user cancels, no Google account is available, or the
 *   request otherwise fails; the caller distinguishes cancellation from a real failure.
 */
private suspend fun requestGoogleIdToken(
    context: Context,
    webClientId: String,
): String {
    val googleIdOption =
        GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    val credential = CredentialManager.create(context).getCredential(context, request).credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Unexpected credential type from Credential Manager: ${credential.type}"
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}

private fun googleSignInErrorMessage(error: Throwable): String =
    when (error) {
        is NoCredentialException -> "No Google account found on this device."
        else -> "Google sign-up failed. Please try again."
    }

@Composable
private fun SignUpContent(
    uiState: SignUpUiState,
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
                OutlinedIconButton(
                    onClick = onBackClick,
                    // OutlinedIconButton's default border reads MaterialTheme.colorScheme.outline,
                    // which is a real theme token but too low-contrast against our charcoal
                    // background -- matching it to onBackground (the same token as the icon
                    // below) keeps the whole button, not just the chevron, visible in dark mode.
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
                    // Explicit transparent container: IconButtonDefaults.outlinedIconButtonColors()'s
                    // own default container reads one of M3's tonal "surface container" roles,
                    // which we've never overridden in Theme.kt -- its baseline value happened to
                    // be visibly light-gray against our light background but blended into our
                    // charcoal one, which is why light and dark looked inconsistent (filled vs.
                    // outline-only) even though neither mode set a color explicitly. Forcing
                    // Transparent here removes that fill in both modes instead of relying on
                    // however that default tone happens to compare to our custom backgrounds.
                    colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color.Transparent),
                ) {
                    // Explicit tint, not the ambient default: outside a Scaffold/Surface
                    // ancestor (as in an isolated @Preview) LocalContentColor falls back to a
                    // hardcoded black, which is invisible against a dark background -- the same
                    // class of bug fixed on WelcomeScreen's wordmark/tagline.
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }

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
                GoogleSignInButton(onClick = onGoogleClick, enabled = !uiState.isBusy)

                Spacer(modifier = Modifier.height(24.dp))
                OrDivider()

                // Form-level errors (account collision, bad credentials, network) show right
                // here -- the first thing under the divider, above both fields -- so they don't
                // require scrolling past the form to notice. Field-level validation ("password
                // too short") stays as supportingText under its own field below; this is only
                // for errors that aren't about one specific field.
                if (uiState.generalError != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    ErrorBanner(uiState.generalError)
                }

                Spacer(modifier = Modifier.height(24.dp))
                LabeledField(
                    label = "Email",
                    value = uiState.email,
                    onValueChange = onEmailChanged,
                    errorText = uiState.emailError,
                    enabled = !uiState.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    placeholder = "johndoe@example.com",
                )

                Spacer(modifier = Modifier.height(16.dp))
                LabeledField(
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

            LogInLink(onLogInClick = onLogInClick)
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

@Composable
private fun OrDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "or with email",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    errorText: String?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            isError = errorText != null,
            placeholder =
                placeholder?.let {
                    {
                        // Explicit muted color rather than OutlinedTextField's own default, so
                        // this stays visibly distinct from real input in both modes regardless
                        // of what the field's default placeholder token happens to resolve to.
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            supportingText = {
                if (errorText != null) {
                    Text(errorText, color = MaterialTheme.colorScheme.error)
                }
            },
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            shape = MaterialTheme.shapes.medium,
        )
    }
}

/**
 * A quiet inline notice for form-level errors -- outlined, not filled, so it reads as "heads up"
 * rather than a dominant alert block. `color = Transparent` (not [MaterialTheme.colorScheme.errorContainer])
 * is deliberate: a full errorContainer fill was the heavier look this replaced.
 */
@Composable
private fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.error,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A neutral outlined button (border/text in [MaterialTheme.colorScheme.outline]/[MaterialTheme.colorScheme.onBackground],
 * not the brand accent) with a small four-color ring standing in for the Google mark, since no
 * official asset or path data was provided for it and this project doesn't load network/image
 * assets. Not [com.repmate.ui.components.RepMateButton]: that component's two variants are both
 * accent-colored, and this button's whole point -- like every real "Continue with Google"
 * button -- is to look neutral, not branded.
 */
@Composable
private fun GoogleSignInButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        border = ButtonDefaults.outlinedButtonBorder(enabled).copy(width = 1.dp),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 24.dp),
    ) {
        GoogleLogoMark()
        Spacer(modifier = Modifier.width(12.dp))
        Text("Continue with Google")
    }
}

/** A simplified four-color ring standing in for the Google "G" mark -- see [GoogleSignInButton]. */
@Composable
private fun GoogleLogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = size.minDimension * 0.3f
        val diameter = size.minDimension - stroke
        val arcTopLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val strokeStyle = Stroke(width = stroke, cap = StrokeCap.Butt)
        drawArc(Color(0xFF4285F4), -90f, 90f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
        drawArc(Color(0xFF34A853), 0f, 90f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
        drawArc(Color(0xFFFBBC05), 90f, 90f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
        drawArc(Color(0xFFEA4335), 180f, 90f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = strokeStyle)
    }
}

@Composable
private fun LogInLink(
    onLogInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Already have an account? ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Log in",
            style =
                MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clickable(onClick = onLogInClick),
        )
    }
}

private val PREVIEW_ERROR_STATE =
    SignUpUiState(
        email = "jess@example.com",
        passwordError = "Password must be at least 6 characters.",
        generalError = "An account with this email already exists.",
    )

@Preview(name = "Light - Default", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SignUpScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        SignUpContent(
            uiState = SignUpUiState(email = "jess@example.com"),
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
            uiState = SignUpUiState(email = "jess@example.com"),
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

private val PREVIEW_LOADING_STATE = SignUpUiState(email = "jess@example.com", isEmailLoading = true)

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
