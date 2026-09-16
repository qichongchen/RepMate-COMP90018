package com.repmate.ui.calibration

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.CalibrationOutcome
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.JumpingJackRepDetector
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme

/**
 * A 5-rep calibration set for one exercise, reached from Home's exercise chips (and from
 * Live Workout's own pre-check) whenever [ExerciseType] has no saved [CalibrationProfile] yet.
 *
 * Split into this stateful wrapper and the stateless [CalibrationContent] below, same reasoning as
 * every other screen in this app: previews render from a plain [CalibrationUiState], no Hilt
 * required.
 *
 * @param exerciseType parsed by the caller (`NavGraph.kt`) from the `calibration/{exerciseType}`
 *   route, the same pattern used for Live Workout's own placeholder.
 * @param onCalibrationComplete invoked once, either after an accepted calibration is persisted or
 *   immediately on "Skip for now" -- both mean "proceed into Live Workout", so this screen doesn't
 *   distinguish them for the caller.
 */
@Composable
fun CalibrationScreen(
    exerciseType: ExerciseType,
    onCalibrationComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(exerciseType) {
        viewModel.onExerciseType(exerciseType)
    }

    LaunchedEffect(uiState) {
        if (uiState is CalibrationUiState.Accepted) {
            onCalibrationComplete()
        }
    }

    CalibrationContent(
        uiState = uiState,
        onFinishClicked = viewModel::onFinishClicked,
        onTryAgainClicked = viewModel::onTryAgainClicked,
        onSkipClicked = onCalibrationComplete,
        modifier = modifier,
    )
}

@Composable
private fun CalibrationContent(
    uiState: CalibrationUiState,
    onFinishClicked: () -> Unit,
    onTryAgainClicked: () -> Unit,
    onSkipClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        is CalibrationUiState.Recording ->
            if (uiState.exerciseType == ExerciseType.JUMPING_JACK) {
                JumpingJackRecordingContent(
                    repsCompleted = uiState.repsCompleted,
                    onFinishClicked = onFinishClicked,
                    onSkipClicked = onSkipClicked,
                    modifier = modifier,
                )
            } else {
                RecordingContent(
                    exerciseType = uiState.exerciseType,
                    repsCompleted = uiState.repsCompleted,
                    onFinishClicked = onFinishClicked,
                    onSkipClicked = onSkipClicked,
                    modifier = modifier,
                )
            }
        is CalibrationUiState.Rejected ->
            RejectedContent(
                reason = uiState.reason,
                onTryAgainClicked = onTryAgainClicked,
                onSkipClicked = onSkipClicked,
                modifier = modifier,
            )
        // Nothing to show: CalibrationScreen's LaunchedEffect(uiState) navigates away the moment
        // this is reached. An empty themed surface avoids a one-frame flash of the prior state
        // while that navigation is still in flight.
        CalibrationUiState.Accepted ->
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

@Composable
private fun RecordingContent(
    exerciseType: ExerciseType,
    repsCompleted: Int,
    onFinishClicked: () -> Unit,
    onSkipClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalibrationScaffold(
        titleBlock = {
            Text(
                text = "Quick calibration",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Everyone moves differently. Do 5 normal ${exerciseType.pluralLowerLabel()} " +
                    "so RepMate can learn your movement.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        repsCompleted = repsCompleted,
        statusText = "${exerciseType.pluralLowerLabel().replaceFirstChar { it.uppercase() }} now, " +
            "we're recording rep ${(repsCompleted + 1).coerceAtMost(CalibrationProfile.REQUIRED_SAMPLES)} " +
            "of ${CalibrationProfile.REQUIRED_SAMPLES}",
        onFinishClicked = onFinishClicked,
        onSkipClicked = onSkipClicked,
        modifier = modifier,
    )
}

/**
 * Same shape as [RecordingContent], plus a pulsing beat ring and a real audible beat for pacing
 * -- see [JumpingJackMetronome] for the audio itself, and [BeatPulseIndicator] for why the two
 * stay in sync.
 */
@Composable
private fun JumpingJackRecordingContent(
    repsCompleted: Int,
    onFinishClicked: () -> Unit,
    onSkipClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current

    // Started once this content enters composition, released the moment it leaves -- whether
    // because calibration finished, was skipped (both swap CalibrationContent to a different
    // branch), or the user backed out of the screen entirely. Skipped in Compose Preview: a
    // preview render has no reason to write a cache file and open a SoundPool.
    DisposableEffect(Unit) {
        if (inPreview) return@DisposableEffect onDispose {}
        val metronome = JumpingJackMetronome(context)
        metronome.start()
        onDispose { metronome.release() }
    }

    CalibrationScaffold(
        titleBlock = {
            Text(
                text = "Quick calibration",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Jumping jacks need a steady beat to count accurately. Follow the pulse, " +
                    "one full jack (out and back) per beat.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            BeatPulseIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${JumpingJackRepDetector.TARGET_BPM} BPM, one beep per full jack",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        repsCompleted = repsCompleted,
        statusText = "Jumping jacks now, we're recording rep " +
            "${(repsCompleted + 1).coerceAtMost(CalibrationProfile.REQUIRED_SAMPLES)} of ${CalibrationProfile.REQUIRED_SAMPLES}",
        onFinishClicked = onFinishClicked,
        onSkipClicked = onSkipClicked,
        modifier = modifier,
    )
}

/**
 * The Recording layout every exercise shares: a centered title/body block (with room for extra
 * content, e.g. jumping jack's beat ring, via [titleBlock]), the 5-dot progress row, the status
 * box, and the pinned Finish/Skip actions at the bottom.
 */
@Composable
private fun CalibrationScaffold(
    titleBlock: @Composable () -> Unit,
    repsCompleted: Int,
    statusText: String,
    onFinishClicked: () -> Unit,
    onSkipClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            titleBlock()
            Spacer(modifier = Modifier.height(24.dp))
            RepIndicatorRow(repsCompleted = repsCompleted)
            Spacer(modifier = Modifier.height(24.dp))
            CalibrationStatusBox(text = statusText)
        }

        val remaining = (CalibrationProfile.REQUIRED_SAMPLES - repsCompleted).coerceAtLeast(0)
        RepMateButton(
            text = if (remaining > 0) "Finish ($remaining more to go)" else "Finish",
            onClick = onFinishClicked,
            enabled = remaining <= 0,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SkipLink(onClick = onSkipClicked)
    }
}

@Composable
private fun RejectedContent(
    reason: CalibrationOutcome.Reason,
    onTryAgainClicked: () -> Unit,
    onSkipClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Let's try that again",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                // Plain language derived from the reason, not the technical enum name or the
                // engine's own log-oriented `detail` string -- see CalibrationUiState.Rejected.
                text = reason.toUserMessage(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        RepMateButton(text = "Try again", onClick = onTryAgainClicked)
        Spacer(modifier = Modifier.height(4.dp))
        SkipLink(onClick = onSkipClicked)
    }
}

private fun CalibrationOutcome.Reason.toUserMessage(): String =
    when (this) {
        CalibrationOutcome.Reason.TOO_FEW_SAMPLES -> "We didn't catch enough reps, let's try again."
        CalibrationOutcome.Reason.UNUSABLE_TIMING -> "Something interrupted that capture, let's try again."
        CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT,
        CalibrationOutcome.Reason.DURATION_INCONSISTENT,
        -> "Those five reps looked pretty different from each other, try to keep a steady, even pace."
    }

private enum class RepIndicatorState { Completed, Current, Upcoming }

@Composable
private fun RepIndicatorRow(
    repsCompleted: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(CalibrationProfile.REQUIRED_SAMPLES) { index ->
            val state =
                when {
                    index < repsCompleted -> RepIndicatorState.Completed
                    index == repsCompleted -> RepIndicatorState.Current
                    else -> RepIndicatorState.Upcoming
                }
            RepIndicatorDot(state = state)
        }
    }
}

@Composable
private fun RepIndicatorDot(
    state: RepIndicatorState,
    modifier: Modifier = Modifier,
) {
    val dotModifier =
        when (state) {
            RepIndicatorState.Completed -> Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
            RepIndicatorState.Current -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            RepIndicatorState.Upcoming -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        }
    Box(
        modifier = modifier.size(32.dp).clip(CircleShape).then(dotModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (state == RepIndicatorState.Completed) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CalibrationStatusBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The visual half of the beat -- see [JumpingJackMetronome] for the audible half. Both read
 * [JumpingJackRepDetector.TARGET_BPM] directly rather than each hardcoding their own copy of the
 * BPM figure, which is what keeps them in sync by construction.
 */
@Composable
private fun BeatPulseIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "beatPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 60_000 / JumpingJackRepDetector.TARGET_BPM, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "beatPulseScale",
    )
    Box(
        modifier =
            modifier
                .size(88.dp)
                .scale(scale)
                .clip(CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun SkipLink(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Skip for now (use default sensitivity)",
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ExerciseType.pluralLowerLabel(): String =
    when (this) {
        ExerciseType.SQUAT -> "squats"
        ExerciseType.PUSHUP -> "push-ups"
        ExerciseType.JUMPING_JACK -> "jumping jacks"
    }

@Preview(name = "Recording - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationRecordingLightPreview() {
    RepMateTheme(darkTheme = false) {
        CalibrationContent(
            uiState = CalibrationUiState.Recording(ExerciseType.SQUAT, repsCompleted = 2),
            onFinishClicked = {},
            onTryAgainClicked = {},
            onSkipClicked = {},
        )
    }
}

@Preview(name = "Recording - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationRecordingDarkPreview() {
    RepMateTheme(darkTheme = true) {
        CalibrationContent(
            uiState = CalibrationUiState.Recording(ExerciseType.SQUAT, repsCompleted = 5),
            onFinishClicked = {},
            onTryAgainClicked = {},
            onSkipClicked = {},
        )
    }
}

@Preview(name = "Jumping jack - Light", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun CalibrationJumpingJackLightPreview() {
    RepMateTheme(darkTheme = false) {
        CalibrationContent(
            uiState = CalibrationUiState.Recording(ExerciseType.JUMPING_JACK, repsCompleted = 3),
            onFinishClicked = {},
            onTryAgainClicked = {},
            onSkipClicked = {},
        )
    }
}

@Preview(name = "Jumping jack - Dark", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun CalibrationJumpingJackDarkPreview() {
    RepMateTheme(darkTheme = true) {
        CalibrationContent(
            uiState = CalibrationUiState.Recording(ExerciseType.JUMPING_JACK, repsCompleted = 3),
            onFinishClicked = {},
            onTryAgainClicked = {},
            onSkipClicked = {},
        )
    }
}

@Preview(name = "Rejected - Inconsistent - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationRejectedInconsistentLightPreview() {
    RepMateTheme(darkTheme = false) {
        CalibrationContent(
            uiState = CalibrationUiState.Rejected(CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT, "amplitudes span 3.4x"),
            onFinishClicked = {},
            onTryAgainClicked = {},
            onSkipClicked = {},
        )
    }
}

@Preview(name = "Rejected - Too few - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationRejectedTooFewDarkPreview() {
    RepMateTheme(darkTheme = true) {
        CalibrationContent(
            uiState = CalibrationUiState.Rejected(CalibrationOutcome.Reason.TOO_FEW_SAMPLES, "got 3 usable reps, need 5"),
            onFinishClicked = {},
            onTryAgainClicked = {},
            onSkipClicked = {},
        )
    }
}
