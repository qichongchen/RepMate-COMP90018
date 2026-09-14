package com.repmate.ui.auth

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Pieces shared by [SignUpScreen] and [LogInScreen] -- the two auth screens deliberately look
 * and behave identically wherever they overlap (back button, Google button, the email/password
 * fields, the error banner, the bottom prompt row), so both build on this one file rather than
 * each keeping its own copy. A fix made here (like the dark-mode contrast bugs caught on
 * SignUpScreen) reaches both screens automatically instead of needing to be repeated.
 */

/** The outline-only circular back button standardized on SignUpScreen: no fill in either theme, just a bordered circle with the chevron. */
@Composable
internal fun AuthBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = modifier,
        // OutlinedIconButton's default border reads MaterialTheme.colorScheme.outline, which is
        // a real theme token but too low-contrast against our charcoal background -- matching it
        // to onBackground (the same token as the icon below) keeps the whole button, not just
        // the chevron, visible in dark mode.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
        // Explicit transparent container: outlinedIconButtonColors()'s own default container
        // reads one of M3's tonal "surface container" roles, which we've never overridden in
        // Theme.kt -- its baseline value was visibly light-gray against our light background but
        // blended into our charcoal one, making light and dark look inconsistent (filled vs.
        // outline-only) even though neither mode set a color explicitly. Forcing Transparent
        // removes that fill in both modes instead of relying on how that default tone happens to
        // compare to our custom backgrounds.
        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Color.Transparent),
    ) {
        // Explicit tint, not the ambient default: outside a Scaffold/Surface ancestor (as in an
        // isolated @Preview) LocalContentColor falls back to a hardcoded black, invisible
        // against a dark background -- the same class of bug fixed on WelcomeScreen's
        // wordmark/tagline.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The "── or with email ──" divider row between the Google button and the email/password fields. */
@Composable
internal fun AuthDivider(
    modifier: Modifier = Modifier,
    label: String = "or with email",
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A labeled, placeholder-aware form field: a small uppercase label above an [OutlinedTextField]. */
@Composable
internal fun AuthLabeledField(
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
internal fun AuthErrorBanner(
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
internal fun AuthGoogleButton(
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

/** A simplified four-color ring standing in for the Google "G" mark -- see [AuthGoogleButton]. */
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

/**
 * The pinned bottom row on both auth screens: a muted prompt plus an underlined, tappable
 * action -- "Already have an account? Log in" on SignUp, "Don't have an account? Sign up" here.
 */
@Composable
internal fun AuthBottomPrompt(
    promptText: String,
    actionText: String,
    onActionClick: () -> Unit,
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
            text = promptText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = actionText,
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clickable(onClick = onActionClick),
        )
    }
}

/**
 * Launches Android's Credential Manager to request a Google ID token, per Google's current
 * recommended sign-in API (superseding the older `GoogleSignInClient`). Shared by both auth
 * screens: the request/response shape doesn't depend on whether this is a sign-up or a log-in.
 *
 * @param webClientId the Firebase project's OAuth Web Client ID -- see the `GOOGLE_WEB_CLIENT_ID`
 *   comment in `app/build.gradle.kts` for where this has to come from.
 * @throws GetCredentialException if the user cancels, no Google account is available, or the
 *   request otherwise fails; callers distinguish cancellation from a real failure.
 */
internal suspend fun requestGoogleIdToken(
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

internal fun googleSignInErrorMessage(error: Throwable): String =
    when (error) {
        is NoCredentialException -> "No Google account found on this device."
        else -> "Google sign-in failed. Please try again."
    }
