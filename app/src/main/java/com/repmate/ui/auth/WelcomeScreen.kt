package com.repmate.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.repmate.R
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateButtonVariant
import com.repmate.ui.theme.RepMateTheme

/**
 * RepMate's entry screen: brand mark up top, then Sign up / Log in / Continue as guest stacked
 * at the bottom.
 *
 * Purely presentational -- it takes three callbacks and has no idea what a "route" is, so it
 * stays reusable and testable independent of navigation. The caller (currently the `welcome`
 * destination in `NavGraph.kt`) decides what each action actually does.
 *
 * @param onSignUp invoked when "Sign up" is tapped.
 * @param onLogIn invoked when "Log in" is tapped.
 * @param onContinueAsGuest invoked when "Continue as guest" is tapped.
 */
@Composable
fun WelcomeScreen(
    onSignUp: () -> Unit,
    onLogIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
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

        // Actions pinned to the bottom of the screen, most to least committal top to bottom.
        RepMateButton(text = "Sign up", onClick = onSignUp)
        Spacer(modifier = Modifier.height(12.dp))
        RepMateButton(text = "Log in", onClick = onLogIn, variant = RepMateButtonVariant.Ghost)
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onContinueAsGuest, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Continue as guest",
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun WelcomeScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        WelcomeScreen(onSignUp = {}, onLogIn = {}, onContinueAsGuest = {})
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun WelcomeScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        WelcomeScreen(onSignUp = {}, onLogIn = {}, onContinueAsGuest = {})
    }
}
