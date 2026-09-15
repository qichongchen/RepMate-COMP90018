package com.repmate.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.repmate.R
import com.repmate.ui.components.LoadingOverlay
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateButtonVariant
import com.repmate.ui.theme.RepMateTheme

/**
 * RepMate's entry screen: brand mark up top, then Sign up / Log in / Continue as guest stacked
 * at the bottom.
 *
 * Split into this stateful wrapper and the stateless [WelcomeContent] below, same reasoning as
 * [SignUpScreen]/`SignUpContent`: previews render the form from a plain [WelcomeUiState] without
 * needing Hilt available.
 *
 * @param onSignUp invoked when "Sign up" is tapped.
 * @param onLogIn invoked when "Log in" is tapped.
 * @param onGuestSignInSuccess invoked once, after anonymous sign-in actually succeeds -- not on
 *   tap. Mirrors `onSignUpSuccess`/`onLogInSuccess` on the other auth screens: the ViewModel
 *   makes the real Firebase call, the caller only navigates once it's actually done.
 */
@Composable
fun WelcomeScreen(
    onSignUp: () -> Unit,
    onLogIn: () -> Unit,
    onGuestSignInSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.guestSignInSucceeded.collect { onGuestSignInSuccess() }
    }

    WelcomeContent(
        uiState = uiState,
        onSignUp = onSignUp,
        onLogIn = onLogIn,
        onContinueAsGuestClick = viewModel::onContinueAsGuestClicked,
        modifier = modifier,
    )
}

@Composable
private fun WelcomeContent(
    uiState: WelcomeUiState,
    onSignUp: () -> Unit,
    onLogIn: () -> Unit,
    onContinueAsGuestClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Box, not Column, is the root: LoadingOverlay is a later sibling here, same pattern as the
    // other auth screens, so it draws on top of the whole screen while the anonymous sign-in
    // call is in flight.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Paint the theme's background explicitly rather than relying on an ancestor
                    // Scaffold to do it -- without this, anything that renders this screen without
                    // that ancestor (an isolated @Preview, a future screenshot test) shows the
                    // correctly-colored white/dark text against whatever default canvas that context
                    // uses instead of our actual charcoal background, which reads as "barely visible".
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            // Brand block: centered in whatever space is left above the actions below, so it holds
            // up whether the screen is a short 360dp-wide phone or a tall 430dp one.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Tinted via Icon()'s built-in ColorFilter rather than the drawable's own fillColor,
                // the same fix as the wordmark/tagline: the vector's XML fillColor is a fixed
                // placeholder, so tinting it at render time with a theme color is what makes it
                // actually adapt between light and dark instead of being hardcoded either way.
                Icon(
                    painter = painterResource(id = R.drawable.ic_welcome_flex),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "REPMATE",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your pocket form coach. Count reps, score form, replay every rep.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (uiState.generalError != null) {
                AuthErrorBanner(uiState.generalError)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Actions pinned to the bottom of the screen, most to least committal top to bottom.
            RepMateButton(text = "Sign up", onClick = onSignUp, enabled = !uiState.isBusy)
            Spacer(modifier = Modifier.height(12.dp))
            RepMateButton(
                text = "Log in",
                onClick = onLogIn,
                variant = RepMateButtonVariant.Ghost,
                enabled = !uiState.isBusy,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = onContinueAsGuestClick,
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Continue as guest",
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (uiState.isBusy) {
            LoadingOverlay()
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun WelcomeScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        WelcomeContent(
            uiState = WelcomeUiState(),
            onSignUp = {},
            onLogIn = {},
            onContinueAsGuestClick = {},
        )
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun WelcomeScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        WelcomeContent(
            uiState = WelcomeUiState(),
            onSignUp = {},
            onLogIn = {},
            onContinueAsGuestClick = {},
        )
    }
}

@Preview(name = "Light - Loading overlay", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun WelcomeScreenLightLoadingPreview() {
    RepMateTheme(darkTheme = false) {
        WelcomeContent(
            uiState = WelcomeUiState(isGuestLoading = true),
            onSignUp = {},
            onLogIn = {},
            onContinueAsGuestClick = {},
        )
    }
}

@Preview(name = "Dark - Loading overlay", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun WelcomeScreenDarkLoadingPreview() {
    RepMateTheme(darkTheme = true) {
        WelcomeContent(
            uiState = WelcomeUiState(isGuestLoading = true),
            onSignUp = {},
            onLogIn = {},
            onContinueAsGuestClick = {},
        )
    }
}
