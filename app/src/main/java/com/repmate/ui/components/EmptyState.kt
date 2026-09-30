package com.repmate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.repmate.ui.theme.RepMateTheme

/**
 * The shared "nothing here yet" body: a centred title, one line of explanation and a single filled
 * primary action. Used by History and Ghost Duel so an empty screen looks and behaves the same
 * wherever it appears; the look is History's original empty state, moved here unchanged.
 *
 * Fills the width it is given and centres its content in the space it is given, so the caller
 * decides the region (typically `Modifier.weight(1f)` beneath a top bar) and any horizontal
 * padding -- this composable adds none of its own.
 *
 * @param title short headline, e.g. "No workouts yet".
 * @param message one line saying what will fill the screen and how, e.g. "Finish a workout and it
 *   will show up here."
 * @param actionLabel label of the button; should say where it goes, since that is the only thing
 *   the button does.
 * @param onAction invoked when the button is tapped.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            RepMateButton(
                text = actionLabel,
                onClick = onAction,
                variant = RepMateButtonVariant.Solid,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun EmptyStatePreviewBody() {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp)) {
        EmptyState(
            title = "No workouts yet",
            message = "Finish a workout and it will show up here.",
            actionLabel = "Start a workout",
            onAction = {},
        )
    }
}

@Preview(name = "Empty state - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 480)
@Composable
private fun EmptyStateLightPreview() {
    RepMateTheme(darkTheme = false) { EmptyStatePreviewBody() }
}

@Preview(name = "Empty state - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 480)
@Composable
private fun EmptyStateDarkPreview() {
    RepMateTheme(darkTheme = true) { EmptyStatePreviewBody() }
}
