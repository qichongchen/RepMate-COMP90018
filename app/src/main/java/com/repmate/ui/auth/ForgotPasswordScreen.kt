package com.repmate.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.ui.components.LoadingOverlay
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

// TODO: the actual "set new password" step happens on a Firebase-hosted page opened from the
// emailed link — not built in this app. No further screen/callback needed here; user returns to
// LogIn manually after resetting.
/**
 * The forgot-password screen: one destination, two internal stages (request vs. confirmation),
 * not two separate routes -- there's no third "email not found" state to route to, since
 * Firebase's `sendPasswordResetEmail` is designed to resolve identically either way. See
 * [ForgotPasswordViewModel]'s class doc for why.
 *
 * @param initialEmail whatever the user had already typed on LogInScreen, if they arrived here
 *   after a failed attempt there; blank if reached any other way. Only used to prefill the field
 *   once -- see [ForgotPasswordViewModel.onInitialEmail].
 * @param onBackClick invoked by both the back chevron and, once sent, "Back to log in".
 */
@Composable
fun ForgotPasswordScreen(
    initialEmail: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForgotPasswordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialEmail) {
        viewModel.onInitialEmail(initialEmail)
    }

    ForgotPasswordContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onEmailChanged = viewModel::onEmailChanged,
        onSendClick = viewModel::onSendClicked,
        onResendClick = viewModel::onResendClicked,
        modifier = modifier,
    )
}

@Composable
private fun ForgotPasswordContent(
    uiState: ForgotPasswordUiState,
    onBackClick: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onResendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same skeleton as SignUp/LogIn: Box root so LoadingOverlay draws on top of everything.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            if (uiState.isSent) {
                ConfirmationStage(
                    email = uiState.email,
                    resendCooldownSeconds = uiState.resendCooldownSeconds,
                    canResend = uiState.canResend,
                    onBackClick = onBackClick,
                    onResendClick = onResendClick,
                )
            } else {
                RequestStage(
                    uiState = uiState,
                    onBackClick = onBackClick,
                    onEmailChanged = onEmailChanged,
                    onSendClick = onSendClick,
                )
            }
        }

        if (uiState.isLoading) {
            LoadingOverlay()
        }
    }
}

@Composable
private fun RequestStage(
    uiState: ForgotPasswordUiState,
    onBackClick: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 24.dp),
    ) {
        AuthBackButton(onClick = onBackClick)

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Reset password",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Enter the email on your account and we'll send you a link to reset your password.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))
        // Only ever a network-failure message -- there is deliberately no "email not found"
        // error, per ForgotPasswordViewModel's class doc.
        if (uiState.generalError != null) {
            AuthErrorBanner(uiState.generalError)
            Spacer(modifier = Modifier.height(24.dp))
        }

        AuthLabeledField(
            label = "Email",
            value = uiState.email,
            onValueChange = onEmailChanged,
            errorText = uiState.emailError,
            enabled = !uiState.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            placeholder = "jess@example.com",
        )

        Spacer(modifier = Modifier.height(24.dp))
        RepMateButton(
            text = "Send reset link",
            onClick = onSendClick,
            enabled = !uiState.isLoading,
        )
    }
}

@Composable
private fun ConfirmationStage(
    email: String,
    resendCooldownSeconds: Int,
    canResend: Boolean,
    onBackClick: () -> Unit,
    onResendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 24.dp),
    ) {
        AuthBackButton(onClick = onBackClick)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.MarkEmailRead,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Check your email",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                // Hedged on purpose -- never confirms the account exists, matching what
                // sendPasswordResetEmail itself does (or doesn't) reveal.
                text =
                    "If an account exists for $email, we've sent a link to reset your " +
                        "password. It may take a few minutes to arrive — check spam too.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (canResend) {
                Text(
                    text = "Resend email",
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.clickable(onClick = onResendClick),
                )
            } else {
                Text(
                    text = "Resend available in ${formatCooldown(resendCooldownSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        RepMateButton(text = "Back to log in", onClick = onBackClick)
    }
}

private fun formatCooldown(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

@Preview(name = "Light - Request", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ForgotPasswordRequestLightPreview() {
    RepMateTheme(darkTheme = false) {
        ForgotPasswordContent(
            uiState = ForgotPasswordUiState(email = "jess@example.com"),
            onBackClick = {},
            onEmailChanged = {},
            onSendClick = {},
            onResendClick = {},
        )
    }
}

@Preview(name = "Dark - Request", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ForgotPasswordRequestDarkPreview() {
    RepMateTheme(darkTheme = true) {
        ForgotPasswordContent(
            uiState = ForgotPasswordUiState(email = "jess@example.com"),
            onBackClick = {},
            onEmailChanged = {},
            onSendClick = {},
            onResendClick = {},
        )
    }
}

private val PREVIEW_SENT_STATE =
    ForgotPasswordUiState(
        email = "jess@example.com",
        isSent = true,
        resendCooldownSeconds = 47,
    )

@Preview(name = "Light - Sent", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ForgotPasswordSentLightPreview() {
    RepMateTheme(darkTheme = false) {
        ForgotPasswordContent(
            uiState = PREVIEW_SENT_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onSendClick = {},
            onResendClick = {},
        )
    }
}

@Preview(name = "Dark - Sent", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ForgotPasswordSentDarkPreview() {
    RepMateTheme(darkTheme = true) {
        ForgotPasswordContent(
            uiState = PREVIEW_SENT_STATE,
            onBackClick = {},
            onEmailChanged = {},
            onSendClick = {},
            onResendClick = {},
        )
    }
}
