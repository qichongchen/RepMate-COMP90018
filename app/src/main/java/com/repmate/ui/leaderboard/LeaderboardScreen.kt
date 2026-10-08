package com.repmate.ui.leaderboard

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.data.repo.LeaderboardEntry
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.theme.RepMateTheme

@Composable
fun LeaderboardScreen(
    onGhostDuelClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LeaderboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LeaderboardContent(
        uiState = uiState,
        onTypeSelected = viewModel::selectLeaderboard,
        onGhostDuelClick = onGhostDuelClick,
        modifier = modifier,
    )
}

/**
 * The leaderboard, stateless so it can be previewed in both themes and in every state.
 *
 * Split out from [LeaderboardScreen] the same way `LiveWorkoutContent` is: the drawing of a
 * screen is the part worth being able to look at without a ViewModel, a signed-in user or
 * Firestore.
 */
@Composable
internal fun LeaderboardContent(
    uiState: LeaderboardUiState,
    onTypeSelected: (LeaderboardType) -> Unit,
    onGhostDuelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "LEADERBOARD",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        LeaderboardTabs(selected = uiState.selectedType, onTypeSelected = onTypeSelected)

        Spacer(modifier = Modifier.height(20.dp))

        // NOTE: not "today". Points are each user's *best session per exercise*, which is a
        // lifetime figure -- see LeaderboardPoints. Labelling it "today" told the reader
        // something the number does not mean.
        Text(
            text = "ALL TIME",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(modifier = Modifier.weight(1f)) {
            when {
                uiState.isUnavailable ->
                    LeaderboardNotice("The leaderboard is unavailable right now. Try again later.")

                uiState.entries.isEmpty() ->
                    LeaderboardNotice(
                        if (uiState.selectedType == LeaderboardType.FRIENDS) {
                            "No friend scores yet. Finish a workout, or add a friend who has."
                        } else {
                            "No scores yet. Finish a workout to get on the board."
                        },
                    )

                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(uiState.entries) { index, entry ->
                            LeaderboardRow(
                                rank = index + 1,
                                entry = entry,
                                isCurrentUser = entry.userId == uiState.currentUserId,
                            )
                            if (index != uiState.entries.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        GhostDuelBanner(onClick = onGhostDuelClick)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * The Friends/Global switch.
 *
 * Each tab is [Modifier.selectable] with [Role.Tab] rather than a bare `clickable` on a `Text`,
 * so TalkBack announces it as a tab and says which one is selected, and the whole 48dp-high
 * half-width area is the touch target rather than just the letters. The selected tab carries an
 * underline in the accent colour: weight alone is a weak signal, especially in dark mode.
 */
@Composable
private fun LeaderboardTabs(
    selected: LeaderboardType,
    onTypeSelected: (LeaderboardType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        LeaderboardType.entries.forEach { type ->
            val isSelected = type == selected
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onTypeSelected(type) },
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.height(44.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                )
            }
        }
    }
}

/** One row: rank, initial, name, points. The signed-in user's own row is tinted and marked. */
@Composable
private fun LeaderboardRow(
    rank: Int,
    entry: LeaderboardEntry,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    // The same low-alpha accent the replay chart uses for its band: readable in
                    // both themes without introducing a colour the theme does not define.
                    if (isCurrentUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                ).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 28.dp),
        )

        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCurrentUser) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color =
                    if (isCurrentUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
        ) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrentUser) {
                Text(
                    text = "you",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = entry.points.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "pts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Ghost Duel entry point.
 *
 * Deliberately the same [RepMateCard] shape as `HomeScreen`'s own ghost-duel banner: a bordered
 * card with a label, a title line and a trailing arrow. It replaces a bare bold `Text` reading
 * `"Start a ghost duel  >"`, which had no container, no ripple boundary and a literal `>`
 * character where an icon belongs -- it read as a stray caption rather than something to tap.
 * Matching Home means the same action looks the same in both places.
 */
@Composable
private fun GhostDuelBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier.clickable(onClickLabel = "Start a ghost duel", onClick = onClick)) {
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
                text = "Race a friend's best set",
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
private fun LeaderboardNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(vertical = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "FRIENDS" / "GLOBAL", kept next to the type so the two cannot drift apart. */
private val LeaderboardType.label: String
    get() =
        when (this) {
            LeaderboardType.FRIENDS -> "FRIENDS"
            LeaderboardType.GLOBAL -> "GLOBAL"
        }

// --- previews ------------------------------------------------------------------------------
// The drawing is what these are for: both themes, and the three states the screen can be in.

private val PREVIEW_STATE =
    LeaderboardUiState(
        entries =
            listOf(
                LeaderboardEntry(userId = "u1", displayName = "Priya", points = 184),
                LeaderboardEntry(userId = "me", displayName = "Mohit", points = 152),
                LeaderboardEntry(userId = "u3", displayName = "Marcus", points = 140),
                LeaderboardEntry(userId = "u4", displayName = "A Very Long Display Name", points = 96),
            ),
        selectedType = LeaderboardType.FRIENDS,
        currentUserId = "me",
    )

@Preview(name = "Friends - light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LeaderboardFriendsLightPreview() {
    RepMateTheme(darkTheme = false) {
        LeaderboardContent(uiState = PREVIEW_STATE, onTypeSelected = {}, onGhostDuelClick = {})
    }
}

@Preview(name = "Friends - dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LeaderboardFriendsDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LeaderboardContent(uiState = PREVIEW_STATE, onTypeSelected = {}, onGhostDuelClick = {})
    }
}

@Preview(name = "Global - dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LeaderboardGlobalDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LeaderboardContent(
            uiState = PREVIEW_STATE.copy(selectedType = LeaderboardType.GLOBAL),
            onTypeSelected = {},
            onGhostDuelClick = {},
        )
    }
}

@Preview(name = "Empty - dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LeaderboardEmptyDarkPreview() {
    RepMateTheme(darkTheme = true) {
        LeaderboardContent(
            uiState = LeaderboardUiState(selectedType = LeaderboardType.FRIENDS),
            onTypeSelected = {},
            onGhostDuelClick = {},
        )
    }
}

@Preview(name = "Unavailable - light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun LeaderboardUnavailableLightPreview() {
    RepMateTheme(darkTheme = false) {
        LeaderboardContent(
            uiState = LeaderboardUiState(isUnavailable = true),
            onTypeSelected = {},
            onGhostDuelClick = {},
        )
    }
}
