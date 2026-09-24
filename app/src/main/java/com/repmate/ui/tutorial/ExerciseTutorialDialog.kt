package com.repmate.ui.tutorial

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.repmate.R
import com.repmate.engine.ExerciseType
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.displayLabel
import com.repmate.ui.theme.LocalRepMateDarkTheme
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * "How to" popup for one exercise: a 4-step illustration, an optional "Don't show this again"
 * checkbox, and a single CTA. One composable reused from both entry points (see
 * `ExerciseTutorial.dc.html`): before a first attempt from Home (checkbox shown, "Continue"), and
 * from Profile's "How exercises work" (no checkbox, "Got it"). What happens after the CTA -- start
 * the workout, go to calibration, persist the don't-show flag -- is the caller's decision, not
 * this dialog's.
 *
 * A centered [Dialog], not a bottom sheet: a sheet reads as "you're about to act", which suits the
 * Home trigger but not someone calmly browsing Profile.
 *
 * The illustration already has its 4 numbered steps and captions drawn into it, so the steps are
 * not repeated as Compose [Text]. Because TalkBack can't read text inside an image, the image's
 * contentDescription spells out the same 4 steps (see [tutorialSteps]).
 *
 * @param showDismissCheckbox whether to show "Don't show this again". Its state is local to this
 *   dialog, starts unchecked, and is reported only through [onContinue].
 * @param ctaLabel the button text, e.g. "Continue" or "Got it".
 * @param onContinue invoked only by the CTA tap, with the checkbox state (always `false` when
 *   [showDismissCheckbox] is false). The one exit a caller should treat as "proceed", and the only
 *   one that carries the checkbox state worth persisting.
 * @param onCancel invoked by back press. Just closes the dialog: no forward navigation, and the
 *   checkbox state is deliberately dropped, even if it was checked -- backing out isn't agreeing
 *   to anything.
 */
@Composable
fun ExerciseTutorialDialog(
    exerciseType: ExerciseType,
    showDismissCheckbox: Boolean,
    ctaLabel: String,
    onContinue: (dontShowAgainChecked: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var dontShowAgainChecked by rememberSaveable { mutableStateOf(false) }

    // NOTE: tapping outside is disabled so a stray tap mid-reading doesn't close the popup. If it's
    // ever enabled, it routes to onCancel like back does, via onDismissRequest.
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        ExerciseTutorialContent(
            exerciseType = exerciseType,
            showDismissCheckbox = showDismissCheckbox,
            dontShowAgainChecked = dontShowAgainChecked,
            onDontShowAgainChanged = { dontShowAgainChecked = it },
            ctaLabel = ctaLabel,
            onCtaClick = { onContinue(dontShowAgainChecked) },
        )
    }
}

/**
 * The dialog's body, split out from [ExerciseTutorialDialog] so previews can render it without a
 * dialog window. Stateless: the checkbox state is hoisted to the caller.
 */
@Composable
private fun ExerciseTutorialContent(
    exerciseType: ExerciseType,
    showDismissCheckbox: Boolean,
    dontShowAgainChecked: Boolean,
    onDontShowAgainChanged: (Boolean) -> Unit,
    ctaLabel: String,
    onCtaClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDarkTheme = LocalRepMateDarkTheme.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "How to: ${exerciseType.displayLabel()}".uppercase(Locale.US),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Image(
                painter = painterResource(id = exerciseType.tutorialIllustration(isDarkTheme)),
                contentDescription = exerciseType.tutorialContentDescription(),
                // Illustrations are square; aspectRatio keeps the full image at dialog width.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small),
            )
            if (showDismissCheckbox) {
                DontShowAgainRow(checked = dontShowAgainChecked, onCheckedChange = onDontShowAgainChanged)
            }
            RepMateButton(text = ctaLabel, onClick = onCtaClick)
        }
    }
}

/** The whole row is the tap target (and one TalkBack "checkbox" node), not just the small box. */
@Composable
private fun DontShowAgainRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = "Don't show this again",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Picks the illustration variant by the app's own dark-theme flag, not the system night mode --
 * see [LocalRepMateDarkTheme] for why `drawable-night/` would pick the wrong one.
 */
@DrawableRes
private fun ExerciseType.tutorialIllustration(isDarkTheme: Boolean): Int =
    when (this) {
        ExerciseType.SQUAT -> if (isDarkTheme) R.drawable.repmate_squat_steps_dark else R.drawable.repmate_squat_steps
        ExerciseType.PUSHUP -> if (isDarkTheme) R.drawable.repmate_pushup_steps_dark else R.drawable.repmate_pushup_steps
        ExerciseType.JUMPING_JACK ->
            if (isDarkTheme) R.drawable.repmate_jumping_jacks_steps_dark else R.drawable.repmate_jumping_jacks_steps
    }

/**
 * The 4 steps as (title, detail) pairs, copied verbatim from `ExerciseTutorial.dc.html`. Squat and
 * jumping jack share steps 1-2 (both are phone-in-pocket) but differ at 3-4; push-up is
 * camera-based, so it differs throughout -- its step 2 is the side-on framing the pose detector
 * needs to read elbow angle.
 */
private fun ExerciseType.tutorialSteps(): List<Pair<String, String>> =
    when (this) {
        ExerciseType.SQUAT ->
            listOf(
                "Grab your phone" to "Hold it, stand tall",
                "Pocket it" to "Slide it fully in",
                "Get ready" to "Feet shoulder-width",
                "Squat down & up" to "We count each rep",
            )
        ExerciseType.PUSHUP ->
            listOf(
                "Prop up your phone" to "Ground level, camera facing you",
                "Side-on, not front-on" to "Reads your arm correctly",
                "Frame your arm" to "Plank, whole arm in view",
                "Push up & down" to "We track elbow angle",
            )
        ExerciseType.JUMPING_JACK ->
            listOf(
                "Grab your phone" to "Hold it, stand tall",
                "Pocket it" to "Slide it fully in",
                "Get ready" to "Feet together",
                "Jump to the beat" to "One jack per beep, 52 BPM",
            )
    }

/** e.g. "How to squat. Step 1, Grab your phone: Hold it, stand tall. Step 2, ..." */
private fun ExerciseType.tutorialContentDescription(): String {
    val steps =
        tutorialSteps().mapIndexed { index, (title, detail) -> "Step ${index + 1}, $title: $detail." }
    return "How to ${displayLabel().lowercase(Locale.US)}. ${steps.joinToString(" ")}"
}

@Composable
private fun TutorialPreview(
    exerciseType: ExerciseType,
    darkTheme: Boolean,
    showDismissCheckbox: Boolean,
) {
    RepMateTheme(darkTheme = darkTheme) {
        // The theme's screen background behind the popup, so the dark previews aren't framed in white.
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(8.dp)) {
            ExerciseTutorialContent(
                exerciseType = exerciseType,
                showDismissCheckbox = showDismissCheckbox,
                dontShowAgainChecked = false,
                onDontShowAgainChanged = {},
                ctaLabel = if (showDismissCheckbox) "Continue" else "Got it",
                onCtaClick = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// Checkbox + "Continue" = Home's before-first-attempt trigger; no checkbox + "Got it" = Profile.

@Preview(name = "Squat - light - Home", showBackground = true, widthDp = 360)
@Composable
private fun SquatLightHomePreview() = TutorialPreview(ExerciseType.SQUAT, darkTheme = false, showDismissCheckbox = true)

@Preview(name = "Squat - dark - Home", showBackground = true, widthDp = 360)
@Composable
private fun SquatDarkHomePreview() = TutorialPreview(ExerciseType.SQUAT, darkTheme = true, showDismissCheckbox = true)

@Preview(name = "Squat - light - Profile", showBackground = true, widthDp = 360)
@Composable
private fun SquatLightProfilePreview() = TutorialPreview(ExerciseType.SQUAT, darkTheme = false, showDismissCheckbox = false)

@Preview(name = "Squat - dark - Profile", showBackground = true, widthDp = 360)
@Composable
private fun SquatDarkProfilePreview() = TutorialPreview(ExerciseType.SQUAT, darkTheme = true, showDismissCheckbox = false)

@Preview(name = "Push-up - light - Home", showBackground = true, widthDp = 360)
@Composable
private fun PushupLightHomePreview() = TutorialPreview(ExerciseType.PUSHUP, darkTheme = false, showDismissCheckbox = true)

@Preview(name = "Push-up - dark - Home", showBackground = true, widthDp = 360)
@Composable
private fun PushupDarkHomePreview() = TutorialPreview(ExerciseType.PUSHUP, darkTheme = true, showDismissCheckbox = true)

@Preview(name = "Push-up - light - Profile", showBackground = true, widthDp = 360)
@Composable
private fun PushupLightProfilePreview() = TutorialPreview(ExerciseType.PUSHUP, darkTheme = false, showDismissCheckbox = false)

@Preview(name = "Push-up - dark - Profile", showBackground = true, widthDp = 360)
@Composable
private fun PushupDarkProfilePreview() = TutorialPreview(ExerciseType.PUSHUP, darkTheme = true, showDismissCheckbox = false)

@Preview(name = "Jumping jack - light - Home", showBackground = true, widthDp = 360)
@Composable
private fun JumpingJackLightHomePreview() =
    TutorialPreview(ExerciseType.JUMPING_JACK, darkTheme = false, showDismissCheckbox = true)

@Preview(name = "Jumping jack - dark - Home", showBackground = true, widthDp = 360)
@Composable
private fun JumpingJackDarkHomePreview() =
    TutorialPreview(ExerciseType.JUMPING_JACK, darkTheme = true, showDismissCheckbox = true)

@Preview(name = "Jumping jack - light - Profile", showBackground = true, widthDp = 360)
@Composable
private fun JumpingJackLightProfilePreview() =
    TutorialPreview(ExerciseType.JUMPING_JACK, darkTheme = false, showDismissCheckbox = false)

@Preview(name = "Jumping jack - dark - Profile", showBackground = true, widthDp = 360)
@Composable
private fun JumpingJackDarkProfilePreview() =
    TutorialPreview(ExerciseType.JUMPING_JACK, darkTheme = true, showDismissCheckbox = false)
