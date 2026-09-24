package com.repmate.ui.history

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
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * One session's full detail: exercise, date, rep count / average score, and every rep's score
 * with its plain-language reasons. Reached only from a History card tap (see `NavGraph.kt`'s
 * `HISTORY` -> `SESSION_DETAIL` navigation) -- never from a post-workout summary, that's Motion
 * Replay's job.
 *
 * Split into this stateful wrapper and the stateless [SessionDetailContent] below, same
 * reasoning as every other screen in this app: previews render from a plain
 * [SessionDetailUiState], no Hilt required.
 *
 * @param sessionId parsed by the caller (`NavGraph.kt`) from the `session_detail/{sessionId}`
 *   route, the same pattern `MotionReplayScreen` uses for its own argument.
 * @param onBackClick pops the nav stack -- this screen has no other way out.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        viewModel.onSessionId(sessionId)
    }

    SessionDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
private fun SessionDetailContent(
    uiState: SessionDetailUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SessionDetailTopBar(exercise = uiState.exercise, onBackClick = onBackClick)

        when {
            uiState.isLoading -> LoadingBody(modifier = Modifier.weight(1f))
            uiState.notFound -> NotFoundBody(modifier = Modifier.weight(1f))
            else ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = uiState.dateLabel + (uiState.durationLabel?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatCard(label = "Reps", value = uiState.repCount.toString(), modifier = Modifier.weight(1f))
                        StatCard(
                            label = "Avg score",
                            value = String.format(Locale.US, "%.1f", uiState.averageScore),
                            valueColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        text = "per rep",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    RepMateCard {
                        uiState.reps.forEachIndexed { index, rep ->
                            RepRow(rep = rep, showDivider = index != uiState.reps.lastIndex)
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
    }
}

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
 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    RepMateCard(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun RepRow(
    rep: RepScore,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
) {
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
                if (rep.reasons.isNotEmpty()) {
                    Text(
                        text = rep.reasons.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = String.format(Locale.US, "%.1f", rep.score),
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

@Preview(name = "Session Detail - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailLightPreview() {
    RepMateTheme(darkTheme = false) {
        SessionDetailContent(uiState = PREVIEW_STATE, onBackClick = {})
    }
}

@Preview(name = "Session Detail - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailDarkPreview() {
    RepMateTheme(darkTheme = true) {
        SessionDetailContent(uiState = PREVIEW_STATE, onBackClick = {})
    }
}

@Preview(name = "Session Detail - Not found", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun SessionDetailNotFoundPreview() {
    RepMateTheme(darkTheme = false) {
        SessionDetailContent(uiState = SessionDetailUiState(isLoading = false, notFound = true), onBackClick = {})
    }
}
