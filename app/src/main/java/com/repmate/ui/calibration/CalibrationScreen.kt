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
import androidx.compose.foundation.layout.ColumnScope
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
import com.repmate.ui.audio.JumpingJackMetronome
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme

/**
 * A 5-rep calibration set for one exercise, reached from Home's exercise chips whenever
 * [ExerciseType] has no saved [CalibrationProfile] yet, and from Profile's "Recalibrate".
 *
 * Every rep shown here is a rep the real detector counted from the live sensor stream -- see
 * [CalibrationViewModel]. Split into this stateful wrapper and the stateless [CalibrationContent]
 * below, same reasoning as every other screen in this app: previews render from a plain
 * [CalibrationUiState], no Hilt required.
 *
 * @param exerciseType parsed by the caller (`NavGraph.kt`) from the `calibration/{exerciseType}`
 *   route.
 * @param onCalibrationComplete invoked once, either after an accepted calibration is persisted or
 *   on "Continue without calibrating" -- both mean "proceed", so this screen doesn't distinguish
 *   them for the caller. Nothing is saved on the second path.
 * @param onBack leaves without proceeding; only offered when [exerciseType] can't be calibrated.
 */
@Composable
fun CalibrationScreen(
    exerciseType: ExerciseType,
    onCalibrationComplete: () -> Unit,
    onBack: () -> Unit,
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
        onStartClicked = viewModel::onStartClicked,
        onTryAgainClicked = viewModel::onTryAgainClicked,
        onContinueWithoutClicked = onCalibrationComplete,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun CalibrationContent(
    uiState: CalibrationUiState,
    onStartClicked: () -> Unit,
    onTryAgainClicked: () -> Unit,
    onContinueWithoutClicked: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // At this level, not inside the per-state branches below, so the beat runs unbroken from the
    // countdown into the capture: the user needs to have found it before rep one, and the
    // detector is only validated at that pace.
    val metronomeOn =
        when (uiState) {
            is CalibrationUiState.CountingDown -> uiState.exerciseType == ExerciseType.JUMPING_JACK
            is CalibrationUiState.Capturing -> uiState.exerciseType == ExerciseType.JUMPING_JACK
            else -> false
        }
    if (metronomeOn) {
        MetronomeEffect()
    }

    when (uiState) {
        is CalibrationUiState.Ready ->
            ReadyContent(uiState.exerciseType, onStartClicked, onContinueWithoutClicked, modifier)
        is CalibrationUiState.CountingDown ->
            CountingDownContent(uiState, onContinueWithoutClicked, modifier)
        is CalibrationUiState.Capturing ->
            CapturingContent(uiState, onContinueWithoutClicked, modifier)
        is CalibrationUiState.Rejected ->
            RejectedContent(uiState.reason, onTryAgainClicked, onContinueWithoutClicked, modifier)
        is CalibrationUiState.Unsupported ->
            UnsupportedContent(uiState.exerciseType, onBack, modifier)
        // Nothing to show: CalibrationScreen's LaunchedEffect(uiState) navigates away the moment
        // this is reached. An empty themed surface avoids a one-frame flash of the prior state
        // while that navigation is still in flight.
        CalibrationUiState.Accepted ->
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

@Composable
private fun ReadyContent(
    exerciseType: ExerciseType,
    onStartClicked: () -> Unit,
    onContinueWithoutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalibrationScaffold(
        modifier = modifier,
        actions = {
            RepMateButton(text = "Start", onClick = onStartClicked)
            Spacer(modifier = Modifier.height(4.dp))
            ContinueWithoutLink(onClick = onContinueWithoutClicked)
        },
    ) {
        Title("Quick calibration")
        Spacer(modifier = Modifier.height(8.dp))
        Body(
            "Do 5 ${exerciseType.pluralLowerLabel()} exactly as you would in a real workout: " +
                "same speed, same depth.",
        )
        Spacer(modifier = Modifier.height(16.dp))
        // The single most important instruction on this screen. Calibration reps performed
        // slowly and deeply "for the test" measured ~5x stronger than the same person's real
        // set, which left the profile tuned to reps they never do again.
        CalibrationStatusBox(
            text = "Don't slow down or go deeper than usual. RepMate learns from these reps, so " +
                "exaggerated ones make it miss your real ones.",
        )
        Spacer(modifier = Modifier.height(16.dp))
        Body(
            if (exerciseType == ExerciseType.JUMPING_JACK) {
                "Tap Start and put your phone in your front pocket. A beat will play: do one full " +
                    "jack (out and back) per beep, starting when the countdown ends."
            } else {
                "Tap Start, put your phone in your front pocket, and begin when the countdown ends."
            },
        )
    }
}

@Composable
private fun CountingDownContent(
    uiState: CalibrationUiState.CountingDown,
    onContinueWithoutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalibrationScaffold(
        modifier = modifier,
        actions = { ContinueWithoutLink(onClick = onContinueWithoutClicked) },
    ) {
        Body("Phone in your front pocket")
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = uiState.secondsLeft.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (uiState.exerciseType == ExerciseType.JUMPING_JACK) {
            Spacer(modifier = Modifier.height(16.dp))
            BeatPulseIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Body("Find the beat: one full jack per beep")
        }
        Spacer(modifier = Modifier.height(24.dp))
        RepIndicatorRow(repsCompleted = 0)
    }
}

@Composable
private fun CapturingContent(
    uiState: CalibrationUiState.Capturing,
    onContinueWithoutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val required = CalibrationProfile.REQUIRED_SAMPLES
    CalibrationScaffold(
        modifier = modifier,
        actions = { ContinueWithoutLink(onClick = onContinueWithoutClicked) },
    ) {
        Title("Go: ${uiState.exerciseType.pluralLowerLabel()} at your normal pace")
        if (uiState.exerciseType == ExerciseType.JUMPING_JACK) {
            Spacer(modifier = Modifier.height(16.dp))
            BeatPulseIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Body("${JumpingJackRepDetector.TARGET_BPM} BPM, one full jack per beep")
        }
        Spacer(modifier = Modifier.height(24.dp))
        RepIndicatorRow(repsCompleted = uiState.repsCompleted)
        Spacer(modifier = Modifier.height(24.dp))
        CalibrationStatusBox(
            text =
                when {
                    uiState.showNoRepHint -> uiState.exerciseType.noRepHint()
                    uiState.isMoving -> "Movement detected…"
                    uiState.repsCompleted == 0 -> "Waiting for rep 1 of $required"
                    else -> "${uiState.repsCompleted} of $required counted, keep going"
                },
        )
    }
}

@Composable
private fun RejectedContent(
    reason: CalibrationOutcome.Reason,
    onTryAgainClicked: () -> Unit,
    onContinueWithoutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalibrationScaffold(
        modifier = modifier,
        actions = {
            RepMateButton(text = "Try again", onClick = onTryAgainClicked)
            Spacer(modifier = Modifier.height(4.dp))
            ContinueWithoutLink(onClick = onContinueWithoutClicked)
        },
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Title("Let's try that again")
        Spacer(modifier = Modifier.height(8.dp))
        // Plain language derived from the reason, not the technical enum name or the engine's
        // own log-oriented `detail` string (which CalibrationViewModel logs instead).
        Body(reason.toUserMessage())
        Spacer(modifier = Modifier.height(8.dp))
        Body("Nothing was saved.")
    }
}

@Composable
private fun UnsupportedContent(
    exerciseType: ExerciseType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CalibrationScaffold(
        modifier = modifier,
        actions = { RepMateButton(text = "Back", onClick = onBack) },
    ) {
        Title("${exerciseType.pluralLowerLabel().replaceFirstChar { it.uppercase() }} aren't available yet")
        Spacer(modifier = Modifier.height(8.dp))
        Body(
            "RepMate can't detect ${exerciseType.pluralLowerLabel()} from your phone's sensors yet, " +
                "so there's nothing to calibrate. Squats and jumping jacks are ready to go.",
        )
    }
}

/** Centered content above bottom-pinned [actions] -- the layout every calibration state shares. */
@Composable
private fun CalibrationScaffold(
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
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
            content = content,
        )
        actions()
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun CalibrationOutcome.Reason.toUserMessage(): String =
    when (this) {
        CalibrationOutcome.Reason.TOO_FEW_SAMPLES -> "We didn't catch enough reps."
        CalibrationOutcome.Reason.UNUSABLE_TIMING -> "Something interrupted that capture."
        CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT ->
            "Those five reps varied too much in depth. Do them the way you'd do a real set, the same each time."
        CalibrationOutcome.Reason.DURATION_INCONSISTENT ->
            "Those five reps varied too much in speed. Do them the way you'd do a real set, the same each time."
    }

private fun ExerciseType.noRepHint(): String =
    when (this) {
        ExerciseType.JUMPING_JACK -> "No reps yet. Keep your phone in your front pocket and jump in time with the beep."
        else -> "No reps yet. Keep your phone in your front pocket and move at your normal pace."
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
 * Plays the jumping-jack beat for as long as it stays in composition. Skipped in Compose Preview:
 * a preview render has no reason to write a cache file and open a SoundPool.
 */
@Composable
private fun MetronomeEffect() {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    DisposableEffect(Unit) {
        if (inPreview) return@DisposableEffect onDispose {}
        val metronome = JumpingJackMetronome(context)
        metronome.start()
        onDispose { metronome.release() }
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
private fun ContinueWithoutLink(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Continue without calibrating (default sensitivity)",
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

@Composable
private fun PreviewOf(
    uiState: CalibrationUiState,
    darkTheme: Boolean,
) {
    RepMateTheme(darkTheme = darkTheme) {
        CalibrationContent(
            uiState = uiState,
            onStartClicked = {},
            onTryAgainClicked = {},
            onContinueWithoutClicked = {},
            onBack = {},
        )
    }
}

@Preview(name = "Ready - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationReadyLightPreview() = PreviewOf(CalibrationUiState.Ready(ExerciseType.SQUAT), darkTheme = false)

@Preview(name = "Counting down - Jumping jack - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationCountingDownDarkPreview() =
    PreviewOf(CalibrationUiState.CountingDown(ExerciseType.JUMPING_JACK, secondsLeft = 3), darkTheme = true)

@Preview(name = "Capturing - moving - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationCapturingLightPreview() =
    PreviewOf(CalibrationUiState.Capturing(ExerciseType.SQUAT, repsCompleted = 2, isMoving = true), darkTheme = false)

@Preview(name = "Capturing - no-rep hint - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationCapturingHintDarkPreview() =
    PreviewOf(CalibrationUiState.Capturing(ExerciseType.SQUAT, repsCompleted = 0, showNoRepHint = true), darkTheme = true)

@Preview(name = "Capturing - Jumping jack - Light", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun CalibrationCapturingJumpingJackPreview() =
    PreviewOf(CalibrationUiState.Capturing(ExerciseType.JUMPING_JACK, repsCompleted = 3), darkTheme = false)

@Preview(name = "Rejected - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationRejectedLightPreview() =
    PreviewOf(
        CalibrationUiState.Rejected(ExerciseType.SQUAT, CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT, "amplitudes span 3.4x"),
        darkTheme = false,
    )

@Preview(name = "Unsupported - Push-up - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CalibrationUnsupportedDarkPreview() = PreviewOf(CalibrationUiState.Unsupported(ExerciseType.PUSHUP), darkTheme = true)
