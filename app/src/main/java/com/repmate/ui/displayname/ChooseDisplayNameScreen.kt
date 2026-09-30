package com.repmate.ui.displayname

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.ui.auth.AuthBackButton
import com.repmate.ui.auth.AuthLabeledField
import com.repmate.ui.components.LoadingOverlay
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme

/**
 * "Choose your display name": one field with a live availability hint and a Continue button.
 * Stateful wrapper around [ChooseDisplayNameContent], which previews can render without Hilt.
 *
 * @param mode how the screen was reached; only [ChooseNameMode.GATE] hides the back button.
 * @param onFinished invoked once the name is claimed (or already the user's). The caller decides
 *   where to go next (onboarding/home for the gate, back to Profile otherwise).
 * @param onBackClick invoked by the on-screen back button (never shown in gate mode).
 */
@Composable
fun ChooseDisplayNameScreen(
    mode: ChooseNameMode,
    onFinished: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChooseDisplayNameViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(mode) { viewModel.start(mode) }
    LaunchedEffect(viewModel) { viewModel.finished.collect { onFinished() } }

    ChooseDisplayNameContent(
        uiState = uiState,
        onNameChanged = viewModel::onNameChanged,
        onContinueClick = viewModel::onContinueClicked,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun ChooseDisplayNameContent(
    uiState: ChooseNameUiState,
    onNameChanged: (String) -> Unit,
    onContinueClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Box so the loading overlay is a later sibling that dims and blocks the whole screen.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 24.dp),
        ) {
            if (uiState.mode.showsBackButton) {
                AuthBackButton(onClick = onBackClick)
                Spacer(modifier = Modifier.height(24.dp))
            } else {
                Spacer(modifier = Modifier.height(40.dp))
            }

            Text(
                text = "Choose your display name",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This is how you'll appear to friends and on the leaderboard. Pick something unique.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))
            AuthLabeledField(
                label = "Display name",
                value = uiState.name,
                onValueChange = onNameChanged,
                // The hint line below carries every message; the field's own error slot would duplicate it.
                errorText = null,
                enabled = !uiState.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                placeholder = "e.g. Alex_92",
            )
            Spacer(modifier = Modifier.height(4.dp))
            HintLine(hint = uiState.hint)

            Spacer(modifier = Modifier.height(32.dp))
            RepMateButton(
                text = "Continue",
                onClick = onContinueClick,
                enabled = uiState.canContinue,
            )
        }

        if (uiState.isSubmitting) LoadingOverlay()
    }
}

@Composable
private fun HintLine(hint: NameHint) {
    val text = hintText(hint) ?: return
    val color =
        when (hint) {
            NameHint.AVAILABLE, NameHint.MINE -> MaterialTheme.colorScheme.primary
            NameHint.TAKEN, NameHint.INVALID_FORMAT, NameHint.UNAVAILABLE, NameHint.CANT_CHECK -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** The copy for each [NameHint]; null when there is nothing to say. */
internal fun hintText(hint: NameHint): String? =
    when (hint) {
        NameHint.IDLE -> null
        NameHint.CHECKING -> "Checking..."
        NameHint.AVAILABLE -> "Available"
        NameHint.MINE -> "This is your name"
        NameHint.TAKEN -> "That name is taken"
        NameHint.INVALID_FORMAT -> "Use 3 to 20 letters, numbers or underscores, with single spaces between words"
        NameHint.UNAVAILABLE -> "That name isn't available"
        NameHint.CANT_CHECK -> "Can't check right now. Check your connection."
    }

@Preview(name = "Gate - Available (light)", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ChooseDisplayNameLightPreview() {
    RepMateTheme(darkTheme = false) {
        ChooseDisplayNameContent(
            uiState = ChooseNameUiState(name = "Alex Smith", hint = NameHint.AVAILABLE),
            onNameChanged = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

@Preview(name = "Rename - Taken (dark)", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ChooseDisplayNameDarkTakenPreview() {
    RepMateTheme(darkTheme = true) {
        ChooseDisplayNameContent(
            uiState = ChooseNameUiState(mode = ChooseNameMode.RENAME, name = "coolguy", hint = NameHint.TAKEN),
            onNameChanged = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}

@Preview(name = "Gate - Invalid (dark)", showBackground = true, widthDp = 360, heightDp = 780, backgroundColor = 0xFF121212)
@Composable
private fun ChooseDisplayNameDarkInvalidPreview() {
    RepMateTheme(darkTheme = true) {
        ChooseDisplayNameContent(
            uiState = ChooseNameUiState(name = "al-ice", hint = NameHint.INVALID_FORMAT),
            onNameChanged = {},
            onContinueClick = {},
            onBackClick = {},
        )
    }
}
