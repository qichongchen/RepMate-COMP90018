package com.repmate.ui.motionreplay

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * Post-workout replay for one session: score, motion curve (when available), and per-rep
 * metrics, with a prev/next scrubber across every rep. Reached from Live Workout's "End workout"
 * (see `NavGraph.kt`'s `LIVE_WORKOUT` -> `MOTION_REPLAY` navigation), carrying the session's id
 * as the `motion_replay/{sessionId}` route argument.
 *
 * Split into this stateful wrapper and the stateless [MotionReplayContent] below, same reasoning
 * as every other screen in this app: previews render from a plain [MotionReplayScreenState], no
 * Hilt required.
 *
 * See [MotionReplayViewModel]'s KDoc for how [sessionId] resolves into a
 * [MotionReplayScreenState], and [MotionReplayContent]'s own KDoc for what each of its three
 * rendered states looks like.
 *
 * @param sessionId parsed by the caller (`NavGraph.kt`) from the `motion_replay/{sessionId}`
 *   route, the same pattern `LiveWorkoutScreen`/`CalibrationScreen` use for their own arguments.
 * @param onBackClick pops the nav stack -- this screen has no other way out.
 * @param onCalibrateClick invoked with the session's exercise when "Calibrate now" is tapped
 *   (only shown for [MotionReplayRepUi.Uncalibrated]); the caller decides where that routes
 *   (Calibration).
 */
@Composable
fun MotionReplayScreen(
    sessionId: String,
    onBackClick: () -> Unit,
    onCalibrateClick: (ExerciseType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MotionReplayViewModel = hiltViewModel(),
) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        viewModel.onSessionId(sessionId)
    }

    MotionReplayContent(
        screenState = screenState,
        onBackClick = onBackClick,
        onCalibrateClick = onCalibrateClick,
        onPrevRepClicked = viewModel::onPrevRepClicked,
        onNextRepClicked = viewModel::onNextRepClicked,
        modifier = modifier,
    )
}

/**
 * [screenState.uiState] `null` renders a loading spinner. Once resolved, three cases per the
 * design canvas:
 * - STATE 3, [MotionReplayUiState.isReplayAvailable] `false`: [NoReplayDataBody] -- the plain
 *   [RepScore]s from [MotionReplayScreenState.fallbackReps], no curve.
 * - STATE 1, the current rep is [MotionReplayRepUi.Calibrated]: [CalibratedReplayBody] -- curve,
 *   calibration band, score, range percent, tempo, reasons. Not reachable yet for a real live
 *   session (see [com.repmate.ui.workout.LiveWorkoutViewModel]'s `frames = null` -- no session
 *   saved today has frame data for the replayer to work with), but rendered anyway so the screen
 *   is ready the moment that gap is closed.
 * - STATE 2, the current rep is [MotionReplayRepUi.Uncalibrated]: [UncalibratedReplayBody] -- rep
 *   count and tempo only, no curve, plus a "Calibrate now" prompt.
 */
@Composable
private fun MotionReplayContent(
    screenState: MotionReplayScreenState,
    onBackClick: () -> Unit,
    onCalibrateClick: (ExerciseType) -> Unit,
    onPrevRepClicked: () -> Unit,
    onNextRepClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = screenState.uiState
    if (uiState == null) {
        Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ReplayTopBar(exercise = null, currentIndex = 0, totalReps = 0, onBackClick = onBackClick)
            LoadingBody(modifier = Modifier.weight(1f))
        }
        return
    }

    val totalReps = if (uiState.isReplayAvailable) uiState.reps.size else screenState.fallbackReps.size

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ReplayTopBar(
            exercise = uiState.exercise,
            currentIndex = uiState.currentIndex,
            totalReps = totalReps,
            onBackClick = onBackClick,
        )

        if (!uiState.isReplayAvailable) {
            NoReplayDataBody(
                reps = screenState.fallbackReps,
                currentIndex = uiState.currentIndex,
                modifier = Modifier.weight(1f),
            )
        } else {
            when (val rep = uiState.reps.getOrNull(uiState.currentIndex)) {
                null -> EmptyReplayBody(modifier = Modifier.weight(1f))
                is MotionReplayRepUi.Calibrated -> CalibratedReplayBody(rep = rep, modifier = Modifier.weight(1f))
                is MotionReplayRepUi.Uncalibrated ->
                    UncalibratedReplayBody(
                        rep = rep,
                        exercise = uiState.exercise,
                        onCalibrateClick = onCalibrateClick,
                        modifier = Modifier.weight(1f),
                    )
            }
        }

        if (totalReps > 0) {
            ReplayScrubber(
                currentIndex = uiState.currentIndex,
                totalReps = totalReps,
                onPrevClicked = onPrevRepClicked,
                onNextClicked = onNextRepClicked,
            )
        }
    }
}

@Composable
private fun ReplayTopBar(
    exercise: ExerciseType?,
    currentIndex: Int,
    totalReps: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReplayBackButton(onClick = onBackClick)
        Text(
            text = topBarLabel(exercise, currentIndex, totalReps),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun topBarLabel(
    exercise: ExerciseType?,
    currentIndex: Int,
    totalReps: Int,
): String {
    val exerciseLabel = exercise?.displayLabel() ?: return "replay"
    return if (totalReps > 0) "$exerciseLabel · rep ${currentIndex + 1} of $totalReps · replay" else "$exerciseLabel · replay"
}

@Composable
private fun ReplayBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyReplayBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No reps to show for this session.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * STATE 3: no captured frames to replay -- [reps] is [MotionReplayScreenState.fallbackReps], the
 * session's plain saved [RepScore]s, not [MotionReplayRepUi]. No curve; just the score, whatever
 * metrics are measurable, the reasons, and a notice explaining why there's no replay.
 */
@Composable
private fun NoReplayDataBody(
    reps: List<RepScore>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    val rep = reps.getOrNull(currentIndex)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NoticeBanner(text = "Motion replay isn't available for this session — showing the saved score only.")

        if (rep == null) {
            Text(
                text = "No reps to show for this session.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ScoreHeader(score = rep.score)
            MetricsCard {
                rep.rangePercentOrNull()?.let { MetricRow(label = "Range of motion", value = "$it%") }
                MetricRow(label = "Tempo", value = formatSeconds(rep.tempoSeconds), showDivider = false)
            }
            ReasonsSection(reasons = rep.reasons)
        }
    }
}

/**
 * STATE 1: a calibrated rep's real motion curve, score, and metrics, with the calibration-range
 * band shaded behind the curve. Not reachable yet for a real live session -- see
 * [com.repmate.ui.workout.LiveWorkoutViewModel]'s `frames = null`.
 */
@Composable
private fun CalibratedReplayBody(
    rep: MotionReplayRepUi.Calibrated,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScoreHeader(score = rep.score)
        RepMateCard {
            Text(
                text = "your motion vs. your calibration range",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AccelerometerCurveChart(
                curve = rep.curve,
                band = rep.calibrationBand,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
        MetricsCard {
            MetricRow(label = "Range of motion", value = "${rep.rangePercent}%")
            MetricRow(label = "Tempo", value = formatSeconds(rep.tempoSeconds), showDivider = false)
        }
        ReasonsSection(reasons = rep.reasons)
    }
}

/**
 * STATE 2: no [com.repmate.engine.CalibrationProfile] for this exercise (or, for now, always --
 * see [MotionReplayViewModel]'s KDoc) -- [MotionReplayRepUi.Uncalibrated] carries no curve, so
 * only rep count and tempo are shown, plus a prompt to calibrate.
 */
@Composable
private fun UncalibratedReplayBody(
    rep: MotionReplayRepUi.Uncalibrated,
    exercise: ExerciseType,
    onCalibrateClick: (ExerciseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NoticeBanner(text = "Not calibrated for this exercise — showing what we can measure without a reference.")
        MetricsCard {
            MetricRow(label = "Rep count", value = rep.repCount.toString())
            MetricRow(label = "Tempo", value = formatSeconds(rep.tempoSeconds), showDivider = false)
        }
        CalibrateNowCard(onClick = { onCalibrateClick(exercise) })
    }
}

@Composable
private fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScoreHeader(
    score: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = formatScore(score),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "/ 10",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun MetricsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    RepMateCard(modifier = modifier, content = content)
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Plain-language, whole-rep-level only -- there is no per-phase attribution to show. */
@Composable
private fun ReasonsSection(
    reasons: List<String>,
    modifier: Modifier = Modifier,
) {
    if (reasons.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "why this score", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        reasons.forEach { reason ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(text = reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun CalibrateNowCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier) {
        Text(text = "Want a real score?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Calibrate this exercise once and we'll compare every rep against your own range.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        RepMateButton(text = "Calibrate now", onClick = onClick)
    }
}

/**
 * [curve] is already rebased onto the rep's own timeline ([rebaseCurve] -- x starts at 0), with
 * [band] shaded behind it. Fewer than two samples can't draw a line, so that case shows a short
 * explanatory label instead of an empty canvas -- degrade gracefully rather than render nothing.
 */
@Composable
private fun AccelerometerCurveChart(
    curve: List<Pair<Long, Float>>,
    band: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    if (curve.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Not enough motion data to draw a curve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val curveColor = MaterialTheme.colorScheme.onBackground
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val minT = curve.first().first
        val maxT = curve.last().first
        val tSpan = (maxT - minT).coerceAtLeast(1L).toFloat()

        val curveMin = curve.minOf { it.second }
        val curveMax = curve.maxOf { it.second }
        val valueMin = minOf(curveMin, band.start)
        val valueMax = maxOf(curveMax, band.endInclusive)
        val valueSpan = (valueMax - valueMin).coerceAtLeast(0.001f)

        fun xFor(tMillis: Long): Float = ((tMillis - minT) / tSpan) * size.width
        fun yFor(value: Float): Float = size.height - ((value - valueMin) / valueSpan) * size.height

        drawLine(axisColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

        val bandPath =
            Path().apply {
                moveTo(0f, yFor(band.endInclusive))
                lineTo(size.width, yFor(band.endInclusive))
                lineTo(size.width, yFor(band.start))
                lineTo(0f, yFor(band.start))
                close()
            }
        drawPath(bandPath, color = bandColor)

        val curvePath =
            Path().apply {
                curve.forEachIndexed { index, (t, value) ->
                    val x = xFor(t)
                    val y = yFor(value)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        drawPath(curvePath, color = curveColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ReplayScrubber(
    currentIndex: Int,
    totalReps: Int,
    onPrevClicked: () -> Unit,
    onNextClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canGoPrev = currentIndex > 0
    val canGoNext = currentIndex < totalReps - 1

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onPrevClicked, enabled = canGoPrev) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous rep",
                tint = if (canGoPrev) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (totalReps > 1) currentIndex / (totalReps - 1).toFloat() else 1f },
            modifier = Modifier.weight(1f).height(4.dp).clip(MaterialTheme.shapes.small),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        IconButton(onClick = onNextClicked, enabled = canGoNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next rep",
                tint = if (canGoNext) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `null` when [RepScore.rangePercent] is the "not measurable" sentinel (uncalibrated at scoring time). */
private fun RepScore.rangePercentOrNull(): Int? = rangePercent.takeIf { it >= 0 }

private fun ExerciseType.displayLabel(): String =
    when (this) {
        ExerciseType.SQUAT -> "Squat"
        ExerciseType.PUSHUP -> "Push-up"
        ExerciseType.JUMPING_JACK -> "Jumping Jack"
    }

private fun formatScore(score: Float): String = String.format(Locale.US, "%.1f", score)

private fun formatSeconds(seconds: Float): String = String.format(Locale.US, "%.1fs", seconds)

// --- Previews ------------------------------------------------------------------------------

private val PREVIEW_CURVE =
    listOf(
        0L to 9.8f,
        120L to 8.4f,
        240L to 5.1f,
        360L to 3.2f,
        480L to 2.8f,
        600L to 4.5f,
        720L to 7.6f,
        840L to 9.9f,
    )

private val CALIBRATED_PREVIEW_STATE =
    MotionReplayScreenState(
        uiState =
            MotionReplayUiState(
                sessionId = "s1",
                exercise = ExerciseType.SQUAT,
                reps =
                    listOf(
                        MotionReplayRepUi.Calibrated(
                            repIndex = 1,
                            repCount = 3,
                            tempoSeconds = 1.4f,
                            curve = PREVIEW_CURVE,
                            calibrationBand = 3.0f..8.5f,
                            score = 7.8f,
                            rangePercent = 92,
                            reasons = listOf("good depth", "rep was rushed"),
                        ),
                    ),
                currentIndex = 0,
                isReplayAvailable = true,
            ),
    )

private val UNCALIBRATED_PREVIEW_STATE =
    MotionReplayScreenState(
        uiState =
            MotionReplayUiState(
                sessionId = "s2",
                exercise = ExerciseType.SQUAT,
                reps = List(3) { i -> MotionReplayRepUi.Uncalibrated(repIndex = i + 1, repCount = 3, tempoSeconds = 1.4f) },
                currentIndex = 1,
                isReplayAvailable = true,
            ),
    )

private val NO_REPLAY_DATA_PREVIEW_STATE =
    MotionReplayScreenState(
        uiState =
            MotionReplayUiState(
                sessionId = "s3",
                exercise = ExerciseType.SQUAT,
                reps = emptyList(),
                currentIndex = 0,
                isReplayAvailable = false,
            ),
        fallbackReps =
            listOf(
                RepScore(
                    repIndex = 0,
                    score = 6.4f,
                    tempoSeconds = 1.8f,
                    rangePercent = 88,
                    pauseSeconds = 0f,
                    reasons = listOf("not deep enough", "good tempo"),
                ),
            ),
    )

private val EMPTY_PREVIEW_STATE =
    MotionReplayScreenState(
        uiState =
            MotionReplayUiState(
                sessionId = "s4",
                exercise = ExerciseType.SQUAT,
                reps = emptyList(),
                currentIndex = 0,
                isReplayAvailable = false,
            ),
        fallbackReps = emptyList(),
    )

@Preview(name = "Calibrated - Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotionReplayCalibratedLightPreview() {
    RepMateTheme(darkTheme = false) {
        MotionReplayContent(
            screenState = CALIBRATED_PREVIEW_STATE,
            onBackClick = {},
            onCalibrateClick = {},
            onPrevRepClicked = {},
            onNextRepClicked = {},
        )
    }
}

@Preview(name = "Calibrated - Dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotionReplayCalibratedDarkPreview() {
    RepMateTheme(darkTheme = true) {
        MotionReplayContent(
            screenState = CALIBRATED_PREVIEW_STATE,
            onBackClick = {},
            onCalibrateClick = {},
            onPrevRepClicked = {},
            onNextRepClicked = {},
        )
    }
}

@Preview(name = "Uncalibrated - Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotionReplayUncalibratedLightPreview() {
    RepMateTheme(darkTheme = false) {
        MotionReplayContent(
            screenState = UNCALIBRATED_PREVIEW_STATE,
            onBackClick = {},
            onCalibrateClick = {},
            onPrevRepClicked = {},
            onNextRepClicked = {},
        )
    }
}

@Preview(name = "No replay data - Dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotionReplayNoReplayDataDarkPreview() {
    RepMateTheme(darkTheme = true) {
        MotionReplayContent(
            screenState = NO_REPLAY_DATA_PREVIEW_STATE,
            onBackClick = {},
            onCalibrateClick = {},
            onPrevRepClicked = {},
            onNextRepClicked = {},
        )
    }
}

@Preview(name = "Loading", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotionReplayLoadingPreview() {
    RepMateTheme(darkTheme = false) {
        MotionReplayContent(
            screenState = MotionReplayScreenState(),
            onBackClick = {},
            onCalibrateClick = {},
            onPrevRepClicked = {},
            onNextRepClicked = {},
        )
    }
}

@Preview(name = "Empty", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MotionReplayEmptyPreview() {
    RepMateTheme(darkTheme = false) {
        MotionReplayContent(
            screenState = EMPTY_PREVIEW_STATE,
            onBackClick = {},
            onCalibrateClick = {},
            onPrevRepClicked = {},
            onNextRepClicked = {},
        )
    }
}
