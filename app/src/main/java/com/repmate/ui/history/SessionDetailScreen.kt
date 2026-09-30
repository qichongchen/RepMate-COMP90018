package com.repmate.ui.history

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.RepScore
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateButtonVariant
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * One session's full detail: exercise, date, rep count / average score, and every rep's score
 * with its plain-language reasons. Serves two entry points, selected by [postWorkout]:
 *
 * - **History mode** (`postWorkout = false`): reached from a History card tap (see `NavGraph.kt`'s
 *   `HISTORY` -> `SESSION_DETAIL` navigation). Exercise-name header with a back arrow.
 * - **Post-workout mode** (`postWorkout = true`): the summary shown straight after "End workout".
 *   "Workout complete" header, no back arrow, and two pinned buttons: "Review reps" (opens Motion
 *   Replay) and "Done" (goes Home). System back behaves like "Done", so the finished workout is
 *   never reachable again.
 *
 * Push-up reps carry a placeholder score (see `ExerciseType.hasFormScoring`), so for push-ups both
 * modes show "not scored yet" instead of the fake number.
 *
 * Split into this stateful wrapper and the stateless [SessionDetailContent] below, same
 * reasoning as every other screen in this app: previews render from a plain
 * [SessionDetailUiState], no Hilt required.
 *
 * @param sessionId parsed by the caller (`NavGraph.kt`) from the `session_detail/{sessionId}`
 *   route, the same pattern `MotionReplayScreen` uses for its own argument.
 * @param postWorkout parsed from the route's optional `postWorkout` query param (default false).
 * @param onBackClick pops the nav stack; only used in History mode, where the back arrow is the
 *   way out.
 * @param onReviewRepsClick post-workout mode only: opens Motion Replay on top of this screen.
 * @param onDoneClick post-workout mode only: goes Home. Also what system back does.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    postWorkout: Boolean,
    onBackClick: () -> Unit,
    onReviewRepsClick: () -> Unit,
    onDoneClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId, postWorkout) {
        viewModel.onSessionId(sessionId, postWorkout)
    }

    // Only intercepts in post-workout mode; in History mode system back pops as usual.
    BackHandler(enabled = postWorkout, onBack = onDoneClick)

    SessionDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onReviewRepsClick = onReviewRepsClick,
        onDoneClick = onDoneClick,
        modifier = modifier,
    )
}

@Composable
private fun SessionDetailContent(
    uiState: SessionDetailUiState,
    onBackClick: () -> Unit,
    onReviewRepsClick: () -> Unit,
    onDoneClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (uiState.postWorkout) {
            PostWorkoutTopBar(subtitle = uiState.postWorkoutSubtitle())
        } else {
            SessionDetailTopBar(exercise = uiState.exercise, onBackClick = onBackClick)
        }

        when {
            uiState.isLoading -> LoadingBody(modifier = Modifier.weight(1f))
            uiState.notFound -> NotFoundBody(modifier = Modifier.weight(1f))
            else -> SessionDetailBody(uiState = uiState, modifier = Modifier.weight(1f))
        }

        if (uiState.postWorkout) {
            PostWorkoutActions(
                safetyCheckInMinutes = uiState.safetyCheckInMinutes,
                reviewEnabled = !uiState.isLoading && !uiState.notFound,
                onReviewRepsClick = onReviewRepsClick,
                onDoneClick = onDoneClick,
            )
        }
    }
}

/** The scrolling part: stats, then the per-rep list. */
@Composable
private fun SessionDetailBody(
    uiState: SessionDetailUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // In post-workout mode the date and duration live in the top bar's subtitle instead.
        if (!uiState.postWorkout) {
            Text(
                text = uiState.dateLabel + (uiState.durationLabel?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(label = "Reps", value = uiState.repCount.toString(), modifier = Modifier.weight(1f))
            if (uiState.hasFormScoring) {
                StatCard(
                    label = "Avg score",
                    value = String.format(Locale.US, "%.1f", uiState.averageScore),
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                StatCard(label = "Avg score", value = NOT_SCORED_DASH, caption = NOT_SCORED_CAPTION, modifier = Modifier.weight(1f))
            }
        }

        Text(
            text = "per rep",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RepMateCard {
            uiState.reps.forEachIndexed { index, rep ->
                RepRow(rep = rep, scored = uiState.hasFormScoring, showDivider = index != uiState.reps.lastIndex)
            }
            if (uiState.reps.isEmpty()) {
                Text(
                    text = "No reps recorded for this session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Exercise · date · duration", skipping whichever parts aren't known yet (e.g. while loading). */
private fun SessionDetailUiState.postWorkoutSubtitle(): String =
    listOfNotNull(exercise.takeIf { it.isNotBlank() }, dateLabel.takeIf { it.isNotBlank() }, durationLabel)
        .joinToString(" · ")

@Composable
private fun SessionDetailTopBar(
    exercise: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
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
        Text(
            text = exercise.ifBlank { "Session" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Post-workout header: no back arrow, because leaving is "Done" (or system back, which does the
 * same) -- an arrow would suggest returning to the workout that has just been saved and closed.
 */
@Composable
private fun PostWorkoutTopBar(
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(
            text = "Workout complete",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pinned below the scrolling list: the optional safety note, then "Review reps" above "Done". */
@Composable
private fun PostWorkoutActions(
    safetyCheckInMinutes: Long?,
    reviewEnabled: Boolean,
    onReviewRepsClick: () -> Unit,
    onDoneClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (safetyCheckInMinutes != null) {
            Text(
                text = "Safety check-in: we'll ask if you're OK in $safetyCheckInMinutes min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RepMateButton(
            text = "Review reps",
            onClick = onReviewRepsClick,
            variant = RepMateButtonVariant.Ghost,
            enabled = reviewEnabled,
        )
        RepMateButton(text = "Done", onClick = onDoneClick, variant = RepMateButtonVariant.Solid)
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NotFoundBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Session not found",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * @param valueColor defaults to plain [MaterialTheme.colorScheme.onSurface]; the caller passes
 *   [MaterialTheme.colorScheme.primary] only for "Avg score", matching Home's LastSessionCard
 *   convention of reserving the accent color for a single highlighted stat, not every number.
 * @param caption optional small line under the value, e.g. "not scored yet" for push-ups.
 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
) {
    RepMateCard(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor)
        if (caption != null) {
            Text(text = caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * @param scored false for exercises without form scoring (push-ups): the row then shows the tempo
 *   and "not scored yet" with "–" in place of the score, and drops the saved reasons, which for
 *   push-ups are only the placeholder's internal note.
 */
@Composable
private fun RepRow(
    rep: RepScore,
    scored: Boolean,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
) {
    val detail =
        if (scored) {
            rep.reasons.joinToString(", ")
        } else {
            String.format(Locale.US, "Tempo %.1fs · %s", rep.tempoSeconds, NOT_SCORED_CAPTION)
        }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Rep ${rep.repIndex + 1}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = if (scored) String.format(Locale.US, "%.1f", rep.score) else NOT_SCORED_DASH,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private const val NOT_SCORED_DASH = "–"
private const val NOT_SCORED_CAPTION = "not scored yet"

// --- Previews ------------------------------------------------------------------------------

private val PREVIEW_STATE =
    SessionDetailUiState(
        exercise = "Squat",
        dateLabel = "Wed 10 Sep",
        durationLabel = "3:12",
        repCount = 3,
        averageScore = 7.5f,
        reps = listOf(
            RepScore(repIndex = 0, score = 7.8f, tempoSeconds = 1.4f, rangePercent = 92, pauseSeconds = 0.3f, reasons = listOf("good depth", "slightly rushed")),
            RepScore(repIndex = 1, score = 6.9f, tempoSeconds = 1.1f, rangePercent = 80, pauseSeconds = 0.1f, reasons = listOf("not deep enough")),
            RepScore(repIndex = 2, score = 7.9f, tempoSeconds = 1.6f, rangePercent = 95, pauseSeconds = 0.4f, reasons = listOf("great control")),
        ),
        isLoading = false,
        notFound = false,
    )

private val POST_WORKOUT_STATE = PREVIEW_STATE.copy(postWorkout = true, safetyCheckInMinutes = 5)

private val PUSHUP_STATE =
    SessionDetailUiState(
        exercise = "Push-up",
        dateLabel = "Wed 10 Sep",
        durationLabel = "1:05",
        repCount = 3,
        averageScore = 10f,
        reps = List(3) { i ->
            RepScore(
                repIndex = i,
                score = 10f,
                tempoSeconds = 2.1f + i * 0.2f,
                rangePercent = 100,
                pauseSeconds = 0f,
                reasons = listOf("not scored: push-ups skip calibration for now"),
            )
        },
        isLoading = false,
        notFound = false,
        hasFormScoring = false,
    )

@Composable
private fun PreviewContent(uiState: SessionDetailUiState, darkTheme: Boolean = false) {
    RepMateTheme(darkTheme = darkTheme) {
        SessionDetailContent(uiState = uiState, onBackClick = {}, onReviewRepsClick = {}, onDoneClick = {})
    }
}

@Preview(name = "Session Detail - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailLightPreview() = PreviewContent(PREVIEW_STATE)

@Preview(name = "Session Detail - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailDarkPreview() = PreviewContent(PREVIEW_STATE, darkTheme = true)

@Preview(name = "Post-workout (squat) - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun PostWorkoutLightPreview() = PreviewContent(POST_WORKOUT_STATE)

@Preview(name = "Post-workout (squat) - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 780)
@Composable
private fun PostWorkoutDarkPreview() = PreviewContent(POST_WORKOUT_STATE, darkTheme = true)

@Preview(name = "Post-workout (push-up, not scored)", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun PostWorkoutPushupPreview() = PreviewContent(PUSHUP_STATE.copy(postWorkout = true))

@Preview(name = "Session Detail (push-up, not scored)", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailPushupPreview() = PreviewContent(PUSHUP_STATE)

@Preview(name = "Session Detail - Not found", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailNotFoundPreview() = PreviewContent(SessionDetailUiState(isLoading = false, notFound = true))
