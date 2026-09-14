package com.repmate.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/**
 * A full-screen modal loading state: dims the screen behind it, blocks every tap from reaching
 * it, and swallows the system back button -- for a request in flight that must not be
 * double-submitted or navigated away from mid-request (a Firebase call, any future network call).
 *
 * Callers compose this conditionally alongside their real content, inside a [Box] so it draws on
 * top -- `Box { ScreenContent(); if (uiState.isBusy) LoadingOverlay() }` -- rather than this
 * taking a `visible` flag. That way back-press blocking, scoped via [BackHandler] to this
 * composable's own presence in composition, turns on and off with no extra wiring at the call
 * site: it's simply not there when nothing is loading.
 *
 * This is meant to be the standard pattern for any screen making a network/Firebase call, not
 * just one screen's bespoke solution.
 */
@Composable
fun LoadingOverlay(modifier: Modifier = Modifier) {
    // Swallows the back press while a request is in flight, rather than letting the user
    // navigate away mid-request.
    BackHandler(onBack = {})

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                // A Box with no pointer input handling lets taps fall through to whatever is
                // drawn behind it -- this no-op clickable is what actually blocks them, not the
                // background color. Without it, someone could tap "Create account" a second time
                // right through the dimmed scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun LoadingOverlayLightPreview() {
    RepMateTheme(darkTheme = false) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            RepMateCard { }
            LoadingOverlay()
        }
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun LoadingOverlayDarkPreview() {
    RepMateTheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            RepMateCard { }
            LoadingOverlay()
        }
    }
}
