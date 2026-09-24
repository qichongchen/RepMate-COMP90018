package com.repmate.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * Every past workout, grouped by calendar month (see [HistoryViewModel] /
 * [groupSessionsIntoSections]). Tapping a card opens Session Detail, not Motion Replay -- Motion Replay
 * needs the session's in-memory frames, which only exist right after a workout finishes, so it's
 * only reachable from the post-workout summary.
 *
 * Split into this stateful wrapper and the stateless [HistoryContent] below, same reasoning as
 * every other screen in this app: previews render from a plain [HistoryUiState], no Hilt required.
 *
 * @param onSessionClick invoked with the tapped session's id; the caller (`NavGraph.kt`) decides
 *   where that routes (Session Detail).
 */
@Composable
fun HistoryScreen(
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryContent(
        uiState = uiState,
        onSessionClick = onSessionClick,
        modifier = modifier,
    )
}

@Composable
private fun HistoryContent(
    uiState: HistoryUiState,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "HISTORY",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.sections.isEmpty()) {
            if (!uiState.isLoading) {
                EmptyHistoryBody(modifier = Modifier.weight(1f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                uiState.sections.forEach { section ->
                    item(key = "header:${section.label}") {
                        Text(
                            text = section.label.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(section.sessions, key = { it.id }) { session ->
                        HistorySessionCard(session = session, onClick = { onSessionClick(session.id) })
                    }
                }

                item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyHistoryBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = "No sessions yet. Finish a workout to see it here.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistorySessionCard(
    session: HistorySessionUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier.clickable(onClickLabel = session.exercise, onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${session.exercise} · ${session.repCount} reps",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = session.dateLabel + (session.durationLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Same heavier display-weight treatment Home's LastSessionCard reserves for a
                // highlighted stat number, in the accent color.
                Text(
                    text = String.format(Locale.US, "%.1f", session.averageScore),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Same forward-entry-point treatment as Home's GhostDuelBanner arrow: tapping
                // this row is the same kind of "go forward" affordance.
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// --- Previews ------------------------------------------------------------------------------

private val PREVIEW_STATE =
    HistoryUiState(
        isLoading = false,
        sections = listOf(
            HistorySection(
                label = "September 2026",
                sessions = listOf(
                    HistorySessionUi(id = "1", exercise = "Squat", repCount = 14, averageScore = 7.8f, dateLabel = "Wed 10 Sep", durationLabel = "3:12"),
                    HistorySessionUi(id = "2", exercise = "Push-up", repCount = 20, averageScore = 6.4f, dateLabel = "Mon 8 Sep", durationLabel = null),
                ),
            ),
            HistorySection(
                label = "August 2026",
                sessions = listOf(
                    HistorySessionUi(id = "3", exercise = "Jumping Jack", repCount = 30, averageScore = 8.2f, dateLabel = "Fri 28 Aug", durationLabel = "2:48"),
                ),
            ),
        ),
    )

@Preview(name = "History - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun HistoryScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        HistoryContent(uiState = PREVIEW_STATE, onSessionClick = {})
    }
}

@Preview(name = "History - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 780)
@Composable
private fun HistoryScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        HistoryContent(uiState = PREVIEW_STATE, onSessionClick = {})
    }
}

@Preview(name = "History - Empty", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 780)
@Composable
private fun HistoryScreenEmptyPreview() {
    RepMateTheme(darkTheme = false) {
        HistoryContent(uiState = HistoryUiState(isLoading = false), onSessionClick = {})
    }
}
