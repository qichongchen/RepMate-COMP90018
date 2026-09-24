package com.repmate.ui.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.example.repmate.BuildConfig
import com.example.repmate.SensorProbeActivity
import com.repmate.engine.ExerciseType
import com.repmate.ui.components.ExerciseChip
import com.repmate.ui.components.displayLabel
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateButtonVariant
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale

/**
 * The main landing screen: exercise picker, ghost duel banner, last session, and today's
 * leaderboard top 3. Hosts the bottom nav bar -- but that's rendered by `RepMateNavGraph`'s
 * Scaffold, not here, since it's shared across Home/History/Leaderboard/Profile.
 *
 * Split into this stateful wrapper and the stateless [HomeContent] below, same reasoning as every
 * other screen in this app: previews render from a plain [HomeUiState], no Hilt required.
 *
 * @param onExerciseSelected invoked with the tapped exercise. The caller (`NavGraph.kt`) decides
 *   whether that leads to Calibration or straight to Live Workout -- this screen just reports the
 *   tap, it doesn't know about calibration profiles at all.
 * @param onProfileClick invoked when the top bar's avatar circle is tapped.
 */
@Composable
fun HomeScreen(
    onExerciseSelected: (ExerciseType) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        onExerciseSelected = onExerciseSelected,
        onProfileClick = onProfileClick,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onExerciseSelected: (ExerciseType) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        HomeTopBar(avatarInitial = uiState.avatarInitial, onProfileClick = onProfileClick)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = uiState.todayLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))
        // Only this section scrolls -- the top bar and date stay pinned, matching the
        // fixed-header/scrolling-body shape every other screen in this app uses.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ExercisePickerCard(onExerciseSelected = onExerciseSelected)
            GhostDuelBanner(opponentName = uiState.ghostDuelOpponentName)

            if (uiState.lastSession != null) {
                Column {
                    Text(
                        text = "last session",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LastSessionCard(session = uiState.lastSession)
                }
            }

            Column {
                Text(
                    text = "leaderboard, today",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LeaderboardCard(entries = uiState.leaderboardTop3, unavailable = uiState.leaderboardUnavailable)
            }

            if (BuildConfig.DEBUG) {
                DebugSensorProbeButton()
            }
        }
    }
}

/**
 * Debug-only bridge to the engine team's sensor bring-up harness, carried over from the old
 * HomePlaceholder now that this is the real Home screen. SensorProbeActivity is a separate
 * Activity that predates this nav graph, not a NavHost destination, so it's reached with a plain
 * Intent rather than navigation. Gated on BuildConfig.DEBUG so it never ships in a release build;
 * delete this the same day SensorProbeActivity itself gets deleted.
 */
@Composable
private fun DebugSensorProbeButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    RepMateButton(
        text = "Open sensor probe (debug)",
        onClick = { context.startActivity(Intent(context, SensorProbeActivity::class.java)) },
        variant = RepMateButtonVariant.Ghost,
        modifier = modifier,
    )
}

@Composable
private fun HomeTopBar(
    avatarInitial: String?,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "REPMATE",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // Tappable, opens Profile. Shows the account's initial when one is derivable (Google
        // sign-up's displayName, or the email's first letter for email/password sign-up, which
        // doesn't collect a name -- see SignUpScreen's NOTE on that); a guest account has
        // neither, so it falls back to a generic person-silhouette icon instead of a fake letter.
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .clickable(onClickLabel = "Profile", role = Role.Button, onClick = onProfileClick),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarInitial != null) {
                Text(
                    text = avatarInitial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    // Decorative here, not a separate label: the Box's own clickable
                    // onClickLabel above already announces "Profile" for the whole circle.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ExercisePickerCard(
    onExerciseSelected: (ExerciseType) -> Unit,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier) {
        Text(
            text = "tap an exercise to start",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // ExerciseType.entries is declared SQUAT, PUSHUP, JUMPING_JACK, which is already the
        // Squat / Push-up / Jumping Jack order asked for -- no manual ordering needed here.
        ExerciseType.entries.forEachIndexed { index, exerciseType ->
            ExerciseChip(
                label = exerciseType.displayLabel(),
                onClick = { onExerciseSelected(exerciseType) },
                icon = exerciseType.pickerIcon(),
            )
            if (index != ExerciseType.entries.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun GhostDuelBanner(
    opponentName: String?,
    modifier: Modifier = Modifier,
) {
    // Static placeholder: no real opponent-matching data source exists yet, so this isn't
    // tappable and doesn't navigate anywhere -- it's content, not yet a real feature entry point.
    RepMateCard(modifier = modifier) {
        Text(
            text = "ghost duel",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Challenge ${opponentName ?: "a friend"}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LastSessionCard(
    session: LastSessionUi,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = session.exercise.displayLabel(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.repCount} reps · avg ${formatScore(session.averageScore)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The big number: same heavier display-weight treatment the theme reserves for
            // "highlighted numbers/stats", in the accent color, per the original brand brief.
            Text(
                text = formatScore(session.averageScore),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LeaderboardCard(
    entries: List<LeaderboardEntryUi>,
    modifier: Modifier = Modifier,
    unavailable: Boolean = false,
) {
    RepMateCard(modifier = modifier) {
        if (unavailable) {
            Text(
                text = "Leaderboard unavailable right now",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${entry.rank}. ${entry.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "${entry.points} pts",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Normal,
                    color = if (entry.isCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index != entries.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// TODO: same placeholder icon (FitnessCenter) for all three exercises for now, per request --
// swap in distinct per-exercise icons once suitable ones are found, same as onboarding's page 1
// icon was upgraded from a reused placeholder to a dedicated one.
private fun ExerciseType.pickerIcon(): ImageVector = Icons.Filled.FitnessCenter

private fun formatScore(score: Float): String = String.format(Locale.US, "%.1f", score)

private val PREVIEW_STATE =
    HomeUiState(
        todayLabel = "Tuesday, September 16",
        avatarInitial = "J",
        lastSession =
            LastSessionUi(
                exercise = ExerciseType.SQUAT,
                repCount = 12,
                averageScore = 8.2f,
            ),
        leaderboardTop3 =
            listOf(
                LeaderboardEntryUi(rank = 1, name = "Priya", points = 1420, isCurrentUser = false),
                LeaderboardEntryUi(rank = 2, name = "You", points = 1305, isCurrentUser = true),
                LeaderboardEntryUi(rank = 3, name = "Marcus", points = 1260, isCurrentUser = false),
            ),
        ghostDuelOpponentName = "Priya",
    )

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun HomeScreenLightPreview() {
    RepMateTheme(darkTheme = false) {
        HomeContent(uiState = PREVIEW_STATE, onExerciseSelected = {}, onProfileClick = {})
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun HomeScreenDarkPreview() {
    RepMateTheme(darkTheme = true) {
        HomeContent(uiState = PREVIEW_STATE, onExerciseSelected = {}, onProfileClick = {})
    }
}
