package com.repmate.ui.ghostduel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.data.repo.Friend
import com.repmate.engine.ExerciseType
import com.repmate.ui.components.EmptyState
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.components.RepMateTopBar
import com.repmate.ui.components.displayLabel
import com.repmate.ui.components.icon
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * Pick a friend and an exercise, see how your best stacks up against theirs, then start a
 * workout to try to beat it. Reached only from Home's Ghost Duel banner (see `NavGraph.kt`'s
 * `HOME` -> `GHOST_DUEL` navigation).
 *
 * The comparison displa./gradlew testDebugUnitTest the current user's local best score
 * and the selected friend's published best score.
 * Loading, missing scores and query failures are shown separately.
 *
 * Split into this stateful wrapper and the stateless [GhostDuelContent] below, same reasoning as
 * every other screen in this app: previews render from a plain [GhostDuelUiState], no Hilt
 * required.
 *
 * @param onBackClick pops the nav stack -- this screen has no other way out.
 * @param onStartWorkoutClick invoked with the selected exercise when "Start Workout to Beat It"
 *   is tapped; the caller (`NavGraph.kt`) routes this through the same calibration-gated flow
 *   Home's exercise chips use, not a second copy of that branching.
 * @param onAddFriendClick invoked by the empty state's "Add friends" button; the caller
 *   (`NavGraph.kt`) opens the Friends screen.
 */
@Composable
fun GhostDuelScreen(
    onBackClick: () -> Unit,
    onStartWorkoutClick: (ExerciseType) -> Unit,
    onAddFriendClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GhostDuelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GhostDuelContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onStartWorkoutClick = onStartWorkoutClick,
        onAddFriendClick = onAddFriendClick,
        onFriendSelected = viewModel::onFriendSelected,
        onExerciseSelected = viewModel::onExerciseSelected,
        modifier = modifier,
    )
}

@Composable
private fun GhostDuelContent(
    uiState: GhostDuelUiState,
    onBackClick: () -> Unit,
    onStartWorkoutClick: (ExerciseType) -> Unit,
    onAddFriendClick: () -> Unit,
    onFriendSelected: (String) -> Unit,
    onExerciseSelected: (ExerciseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Toggles GhostDuelOpponentPicker -- a dialog, not a NavGraph route, so its visibility is
    // owned here rather than by the nav graph (see ProfileScreen's RecalibrateExercisePicker for
    // the same "caller owns the boolean" shape applied to an actual route composable instead).
    var showOpponentPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RepMateTopBar(title = "Ghost Duel", onBackClick = onBackClick)

        if (uiState.friends.isEmpty()) {
            if (!uiState.isLoading) {
                EmptyFriendsBody(onAddFriendClick = onAddFriendClick, modifier = Modifier.weight(1f))
            }
        } else {
            val selectedFriend = uiState.friends.firstOrNull { it.userId == uiState.selectedFriendId }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OpponentField(
                    selectedFriendName = selectedFriend?.displayName,
                    onClick = { showOpponentPicker = true },
                )
                ExercisePickerSection(
                    selectedExercise = uiState.selectedExercise,
                    onExerciseSelected = onExerciseSelected,
                )
                ComparisonSection(
                    yourBest = uiState.yourBest,
                    friendsBest = uiState.friendsBest,
                    friendName = selectedFriend?.displayName ?: "opponent",
                    isScoreLoading = uiState.isScoreLoading,
                    yourScoreError = uiState.yourScoreError,
                    friendScoreError = uiState.friendScoreError,
                )
                RepMateButton(
                    text = "Start Workout to Beat It",
                    onClick = { onStartWorkoutClick(uiState.selectedExercise) },
                    enabled = uiState.selectedFriendId != null,
                )
            }

            if (showOpponentPicker) {
                GhostDuelOpponentPicker(
                    friends = uiState.friends,
                    onFriendPicked = onFriendSelected,
                    onDismissRequest = { showOpponentPicker = false },
                )
            }
        }
    }
}

/**
 * No friends yet. Uses the shared [EmptyState]; the action is labelled "Add friends" because it
 * opens the Friends screen (see `onAddFriendClick` in `NavGraph.kt`).
 * The horizontal padding is applied here because this screen, unlike History, does not pad its
 * whole body.
 */
@Composable
private fun EmptyFriendsBody(
    onAddFriendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = "No friends yet",
        message = "Add a friend to start a Ghost Duel.",
        actionLabel = "Add friends",
        onAction = onAddFriendClick,
        modifier = modifier.padding(horizontal = 24.dp),
    )
}

/**
 * The opponent picker collapsed to a single tappable field -- was a stacked column of every
 * friend (fine for 2 friends, not fine for a real friend list); tapping it now opens
 * [GhostDuelOpponentPicker] instead. Same `.card`-and-chevron affordance `HistorySessionCard`
 * already uses for "tap this to go further."
 */
@Composable
private fun OpponentField(
    selectedFriendName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "opponent",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        RepMateCard(modifier = Modifier.clickable(onClickLabel = "Change opponent", onClick = onClick)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = selectedFriendName ?: "Select opponent",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Same forward-entry-point treatment as HistorySessionCard's chevron: tapping
                // this field is the same kind of "go forward" affordance.
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Three exercise rows, same layout/spacing as Home's `ExercisePickerCard` -- but this picker
 * persists a selection rather than starting a workout the instant a row is tapped, so it's built
 * from [SelectableRow] rather than reusing `ExerciseChip` (a tap-to-immediately-start row with no
 * selected state, per Home's own 2-tap-rule comment).
 */
@Composable
private fun ExercisePickerSection(
    selectedExercise: ExerciseType,
    onExerciseSelected: (ExerciseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "exercise",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        RepMateCard {
            // ExerciseType.entries is declared SQUAT, PUSHUP, JUMPING_JACK, already the
            // Squat / Push-up / Jumping Jack order Home's own picker relies on the same way.
            ExerciseType.entries.forEachIndexed { index, exerciseType ->
                SelectableRow(
                    label = exerciseType.displayLabel(),
                    selected = exerciseType == selectedExercise,
                    onClick = { onExerciseSelected(exerciseType) },
                    icon = exerciseType.icon(),
                )
                if (index != ExerciseType.entries.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * A full-width selectable row shared by the opponent and exercise pickers: unselected rows sit
 * on [MaterialTheme.colorScheme.surface] with a 1dp [MaterialTheme.colorScheme.outline] border,
 * the selected row fills with [MaterialTheme.colorScheme.primary] and drops the border --
 * mirroring [com.repmate.ui.components.RepMateButton]'s own Solid-variant colors, the closest
 * existing precedent in this app for "this is the chosen one".
 */
@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = MaterialTheme.shapes.medium

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(label, style = MaterialTheme.typography.titleLarge, color = contentColor)
    }
}


@Composable
private fun ComparisonSection(
    yourBest: GhostDuelBestScore?,
    friendsBest: GhostDuelBestScore?,
    friendName: String,
    isScoreLoading: Boolean,
    yourScoreError: Boolean,
    friendScoreError: Boolean,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ComparisonColumn(
                label = "your best",
                best = yourBest,
                scoreColor = highlightColorFor(
                    mine = yourBest,
                    other = friendsBest
                ),
                isLoading = isScoreLoading && yourBest == null && !yourScoreError,
                hasError = yourScoreError,
                modifier = Modifier.weight(1f),
            )

            ComparisonColumn(
                label = "$friendName's best",
                best = friendsBest,
                scoreColor = highlightColorFor(
                    mine = friendsBest,
                    other = yourBest
                ),
                isLoading = isScoreLoading &&
                        friendsBest == null &&
                        !friendScoreError,
                hasError = friendScoreError,
                modifier = Modifier.weight(1f),
            )
        }
    }
}


/** [MaterialTheme.colorScheme.primary] only when [mine] is strictly higher than [other]; a tie or either side missing gets the plain color -- same "exactly one highlighted number" rule as `LeaderboardCard`'s current-user row. */
@Composable
private fun highlightColorFor(mine: GhostDuelBestScore?, other: GhostDuelBestScore?): Color {
    val isStrictlyHigher = mine != null && other != null && mine.score > other.score
    return if (isStrictlyHigher) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
}


@Composable
private fun ComparisonColumn(
    label: String,
    best: GhostDuelBestScore?,
    scoreColor: Color,
    isLoading: Boolean,
    hasError: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isLoading -> {
                ScoreStatusBox("Loading...")
            }

            hasError -> {
                ScoreStatusBox("Failed to load score")
            }

            best == null -> {
                ScoreStatusBox("No score yet")
            }

            else -> {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1f/10",
                        best.score
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor,
                )

                Text(
                    text = "${best.reps} reps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun ScoreStatusBox(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                MaterialTheme.shapes.medium
            )
            .padding(
                horizontal = 12.dp,
                vertical = 16.dp
            ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Previews ------------------------------------------------------------------------------

private val PREVIEW_STATE =
    GhostDuelUiState(
        isLoading = false,
        friends = listOf(
            Friend(userId = "u1", displayName = "Priya"),
            Friend(userId = "u2", displayName = "Marcus"),
        ),
        selectedFriendId = "u1",
        selectedExercise = ExerciseType.SQUAT,
        yourBest = null,
        friendsBest = null,
    )

@Preview(name = "Ghost Duel - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 820)
@Composable
private fun GhostDuelScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        GhostDuelContent(
            uiState = PREVIEW_STATE,
            onBackClick = {},
            onStartWorkoutClick = {},
            onAddFriendClick = {},
            onFriendSelected = {},
            onExerciseSelected = {},
        )
    }
}

@Preview(name = "Ghost Duel - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 820)
@Composable
private fun GhostDuelScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        GhostDuelContent(
            uiState = PREVIEW_STATE,
            onBackClick = {},
            onStartWorkoutClick = {},
            onAddFriendClick = {},
            onFriendSelected = {},
            onExerciseSelected = {},
        )
    }
}

@Preview(name = "Ghost Duel - Empty", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 820)
@Composable
private fun GhostDuelScreenEmptyPreview() {
    RepMateTheme(darkTheme = false) {
        GhostDuelContent(
            uiState = GhostDuelUiState(isLoading = false),
            onBackClick = {},
            onStartWorkoutClick = {},
            onAddFriendClick = {},
            onFriendSelected = {},
            onExerciseSelected = {},
        )
    }
}

@Preview(
    name = "Ghost Duel - Scores",
    showBackground = true,
    widthDp = 360,
    heightDp = 820
)

@Composable
private fun GhostDuelScoresPreview() {
    RepMateTheme(darkTheme = false) {
        GhostDuelContent(
            uiState = PREVIEW_STATE.copy(
                yourBest = GhostDuelBestScore(
                    score = 8.5f,
                    reps = 20
                ),
                friendsBest = GhostDuelBestScore(
                    score = 9.2f,
                    reps = 15
                )
            ),
            onBackClick = {},
            onStartWorkoutClick = {},
            onAddFriendClick = {},
            onFriendSelected = {},
            onExerciseSelected = {}
        )
    }
}