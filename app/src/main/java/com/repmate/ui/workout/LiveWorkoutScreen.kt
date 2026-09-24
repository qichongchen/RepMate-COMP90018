package com.repmate.ui.workout

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.ExerciseType
import com.repmate.engine.JumpingJackRepDetector
import com.repmate.engine.RepPhase
import com.repmate.engine.RepScore
import com.repmate.ui.audio.JumpingJackMetronome
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.ScreenLockOverlay
import com.repmate.ui.components.displayLabel
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * The active-workout screen: real sensor data in, real detected/scored reps out. See
 * [LiveWorkoutViewModel]'s KDoc for the data-wiring details.
 *
 * Split into this stateful wrapper and the stateless [LiveWorkoutContent] below, same reasoning
 * as every other screen in this app: previews render from a plain [LiveWorkoutUiState], no Hilt
 * required.
 *
 * Follows the ambient [RepMateTheme] like every other screen -- an earlier wireframe called for
 * this one to always be pure black regardless of the user's light/dark preference, which has
 * since been reversed; nothing here overrides the theme anymore.
 *
 * The lock button in the bottom row is a pocket-safety lock -- see [ScreenLockOverlay]. Its state
 * is local `remember` state here, not part of [LiveWorkoutUiState], so the screen always opens
 * unlocked.
 *
 * @param exerciseType parsed by the caller (`NavGraph.kt`) from the `live_workout/{exerciseType}`
 *   route, the same pattern `CalibrationScreen` uses.
 * @param onWorkoutFinished invoked once, with the session's id, once "End workout" has actually
 *   saved it -- the caller decides what route that leads to (Motion Replay).
 */
@Composable
fun LiveWorkoutScreen(
    exerciseType: ExerciseType,
    onWorkoutFinished: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveWorkoutViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var screenLocked by remember { mutableStateOf(false) }

    LaunchedEffect(exerciseType) {
        viewModel.onExerciseType(exerciseType)
    }

    LaunchedEffect(viewModel) {
        viewModel.workoutFinished.collect { sessionId -> onWorkoutFinished(sessionId) }
    }

    LiveWorkoutContent(
        uiState = uiState,
        onPauseResumeClicked = viewModel::onPauseResumeClicked,
        onEndWorkoutClicked = viewModel::onEndWorkoutClicked,
        screenLocked = screenLocked,
        onScreenLockToggled = { screenLocked = !screenLocked },
        modifier = modifier,
    )
}

@Composable
private fun LiveWorkoutContent(
    uiState: LiveWorkoutUiState,
    onPauseResumeClicked: () -> Unit,
    onEndWorkoutClicked: () -> Unit,
    screenLocked: Boolean,
    onScreenLockToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.exercise == ExerciseType.JUMPING_JACK) {
        JumpingJackMetronomeEffect()
    }

    ScreenLockOverlay(
        screenLocked = screenLocked,
        onScreenLockToggled = onScreenLockToggled,
        scrimColor = MaterialTheme.colorScheme.scrim,
        iconTint = MaterialTheme.colorScheme.onBackground,
        outlineColor = MaterialTheme.colorScheme.outline,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            TopRow(exercise = uiState.exercise, elapsedMillis = uiState.elapsedMillis)

            if (uiState.exercise == ExerciseType.JUMPING_JACK) {
                Spacer(modifier = Modifier.height(16.dp))
                JumpingJackBeatRow()
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RepProgressRing(repCount = uiState.repCount, progressFraction = uiState.phase.progressFraction())
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "phase: ${uiState.phase.name.lowercase(Locale.US)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    FeedbackIndicator(icon = Icons.Filled.Vibration, label = "buzz on rep")
                    FeedbackIndicator(icon = Icons.Filled.RecordVoiceOver, label = "beep count")
                }
                Spacer(modifier = Modifier.height(24.dp))
                LastRepFeedbackBox(repCount = uiState.repCount, lastRepScore = uiState.lastRepScore)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PauseResumeButton(isPaused = uiState.isPaused, onClick = onPauseResumeClicked)
                RepMateButton(
                    text = "End workout",
                    onClick = onEndWorkoutClicked,
                    modifier = Modifier.weight(1f),
                )
                ScreenLockButton()
            }
        }
    }
}

/**
 * Starts/stops the shared [JumpingJackMetronome] with this composable's presence in the tree --
 * present for the whole time [LiveWorkoutContent] shows [ExerciseType.JUMPING_JACK], regardless
 * of [LiveWorkoutUiState.isPaused]. The beat is not paused with the rest of the workout: pausing
 * that too would need pause/resume on the shared metronome class itself, which
 * `CalibrationScreen`'s use of it doesn't need -- kept simple rather than growing that class's
 * API for a case this screen alone has.
 */
@Composable
private fun JumpingJackMetronomeEffect() {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current

    DisposableEffect(Unit) {
        if (inPreview) return@DisposableEffect onDispose {}
        val metronome = JumpingJackMetronome(context)
        metronome.start()
        onDispose { metronome.release() }
    }
}

@Composable
private fun TopRow(
    exercise: ExerciseType,
    elapsedMillis: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = exercise.displayLabel(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = formatElapsed(elapsedMillis),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun JumpingJackBeatRow(modifier: Modifier = Modifier) {
    // Pulses by scale, not alpha: an alpha fade blends the primary lime accent toward whatever
    // sits behind it, which read fine against the screen's old pure-black background but is low
    // contrast against the light theme's near-white one (see CalibrationScreen's
    // BeatPulseIndicator, which pulses the same way for the same reason).
    val infiniteTransition = rememberInfiniteTransition(label = "beatDotPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 60_000 / JumpingJackRepDetector.TARGET_BPM),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "beatDotScale",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .scale(scale)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "jumping jack only, ${JumpingJackRepDetector.TARGET_BPM} BPM, keep pace with the beat",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun RepPhase.progressFraction(): Float =
    when (this) {
        RepPhase.IDLE -> 0f
        RepPhase.DESCENDING -> 0.25f
        RepPhase.BOTTOM -> 0.5f
        RepPhase.ASCENDING -> 0.75f
        RepPhase.TOP -> 1f
    }

@Composable
private fun RepProgressRing(
    repCount: Int,
    progressFraction: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val progressColor = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progressFraction,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            text = repCount.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun FeedbackIndicator(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    // Visual markers only -- see LiveWorkoutViewModel/RepFeedback for the real triggers. Static
    // by design: not driven by any per-rep UI state, since a fired-or-not flag for a one-shot
    // effect isn't state worth modelling.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LastRepFeedbackBox(
    repCount: Int,
    lastRepScore: RepScore?,
    modifier: Modifier = Modifier,
) {
    val text =
        if (lastRepScore != null) {
            "rep $repCount: ${lastRepScore.reasons.joinToString(", ")}"
        } else {
            "no reps yet"
        }
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

@Composable
private fun PauseResumeButton(
    isPaused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        Icon(
            imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (isPaused) "Resume" else "Pause",
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(Locale.US, minutes, seconds)
}

private val SQUAT_PREVIEW_STATE =
    LiveWorkoutUiState(
        exercise = ExerciseType.SQUAT,
        repCount = 4,
        phase = RepPhase.DESCENDING,
        elapsedMillis = 95_000L,
        lastRepScore =
            RepScore(
                repIndex = 3,
                score = 8.4f,
                tempoSeconds = 1.6f,
                rangePercent = 96,
                pauseSeconds = 0f,
                reasons = listOf("good depth", "good tempo"),
            ),
    )

@Preview(name = "Squat - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun LiveWorkoutSquatLightPreview() {
    RepMateTheme(darkTheme = false) {
        LiveWorkoutContent(uiState = SQUAT_PREVIEW_STATE, onPauseResumeClicked = {}, onEndWorkoutClicked = {}, screenLocked = false, onScreenLockToggled = {})
    }
}

@Preview(name = "Squat - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun LiveWorkoutSquatDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LiveWorkoutContent(uiState = SQUAT_PREVIEW_STATE, onPauseResumeClicked = {}, onEndWorkoutClicked = {}, screenLocked = false, onScreenLockToggled = {})
    }
}

private val JUMPING_JACK_PAUSED_PREVIEW_STATE =
    LiveWorkoutUiState(
        exercise = ExerciseType.JUMPING_JACK,
        repCount = 7,
        phase = RepPhase.IDLE,
        elapsedMillis = 42_000L,
        isPaused = true,
        lastRepScore =
            RepScore(
                repIndex = 6,
                score = 10f,
                tempoSeconds = 1.1f,
                rangePercent = -1,
                pauseSeconds = 0f,
                reasons = listOf("not calibrated: depth not scored", "good tempo"),
            ),
    )

@Preview(name = "Jumping jack - Paused - Light", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun LiveWorkoutJumpingJackPausedLightPreview() {
    RepMateTheme(darkTheme = false) {
        LiveWorkoutContent(uiState = JUMPING_JACK_PAUSED_PREVIEW_STATE, onPauseResumeClicked = {}, onEndWorkoutClicked = {}, screenLocked = false, onScreenLockToggled = {})
    }
}

@Preview(name = "Jumping jack - Paused - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun LiveWorkoutJumpingJackPausedDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LiveWorkoutContent(uiState = JUMPING_JACK_PAUSED_PREVIEW_STATE, onPauseResumeClicked = {}, onEndWorkoutClicked = {}, screenLocked = false, onScreenLockToggled = {})
    }
}

@Preview(name = "Squat - Screen locked - Dark", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun LiveWorkoutSquatLockedDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LiveWorkoutContent(
            uiState = SQUAT_PREVIEW_STATE,
            onPauseResumeClicked = {},
            onEndWorkoutClicked = {},
            screenLocked = true,
            onScreenLockToggled = {},
        )
    }
}
