package com.repmate.ui.ghostduel

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.repmate.data.repo.Friend
import com.repmate.ui.theme.RepMateTheme

/**
 * Full-screen "pick a friend" search + list, reached by tapping Ghost Duel's opponent field.
 * Solves the same "pick one of several things and come back" problem
 * [com.repmate.ui.profile.RecalibrateExercisePicker] already solves for exercises -- same
 * callback/toggle shape ([onFriendPicked]/[onDismissRequest], caller owns the boolean, no
 * NavGraph route -- see [GhostDuelScreen]'s `showOpponentPicker`) -- but not its container. That
 * picker is a compact [androidx.compose.material3.AlertDialog], fine for exactly 3 fixed
 * exercises; a real friend list needs to scroll and search, so this is a plain [Dialog] with
 * `usePlatformDefaultWidth = false` wrapping a full-size [Surface], which is what actually reads
 * as "opens another screen" rather than a bigger version of the same dialog card.
 *
 * @param friends the already-loaded friend list -- [GhostDuelViewModel] already collects
 *   `observeFriends()` once for [GhostDuelScreen], so this does not re-collect it a second time.
 * @param onFriendPicked invoked with the tapped row's [Friend.userId].
 * @param onDismissRequest invoked to close the picker -- on the system back gesture/scrim, and
 *   right after [onFriendPicked] fires for a tapped row.
 */
@Composable
fun GhostDuelOpponentPicker(
    friends: List<Friend>,
    onFriendPicked: (String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    // Plain client-side filter -- the list is already fully loaded locally, no backend search
    // needed for a friend count this small.
    val filteredFriends =
        remember(friends, query) {
            if (query.isBlank()) friends else friends.filter { it.displayName.contains(query, ignoreCase = true) }
        }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OpponentPickerTopBar(onDismissRequest = onDismissRequest)

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    placeholder = { Text("Search friends") },
                    singleLine = true,
                )

                if (filteredFriends.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No matches",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        items(filteredFriends, key = { it.userId }) { friend ->
                            FriendRow(
                                friend = friend,
                                onClick = {
                                    onFriendPicked(friend.userId)
                                    onDismissRequest()
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Same top-bar shape as `SessionDetailTopBar` -- a bordered circular button plus a bold title. The button closes the dialog rather than popping a back stack (there isn't one here), but keeps the same back-arrow look for visual consistency with every other screen's "leave this" affordance. */
@Composable
private fun OpponentPickerTopBar(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = onDismissRequest,
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
            text = "Select Opponent",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = friend.displayName,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = friend.displayName, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

// --- Previews ------------------------------------------------------------------------------

private val PREVIEW_FRIENDS =
    listOf(
        Friend(userId = "u1", displayName = "Clair"),
        Friend(userId = "u2", displayName = "Jasper"),
        Friend(userId = "u3", displayName = "Lisa"),
        Friend(userId = "u4", displayName = "Mohit"),
    )

@Preview(name = "Opponent Picker - Light", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 820)
@Composable
private fun GhostDuelOpponentPickerLightPreview() {
    RepMateTheme(darkTheme = false) {
        GhostDuelOpponentPicker(friends = PREVIEW_FRIENDS, onFriendPicked = {}, onDismissRequest = {})
    }
}

@Preview(name = "Opponent Picker - Dark", showBackground = true, backgroundColor = 0xFF121212, widthDp = 360, heightDp = 820)
@Composable
private fun GhostDuelOpponentPickerDarkPreview() {
    RepMateTheme(darkTheme = true) {
        GhostDuelOpponentPicker(friends = PREVIEW_FRIENDS, onFriendPicked = {}, onDismissRequest = {})
    }
}

@Preview(name = "Opponent Picker - No matches", showBackground = true, backgroundColor = 0xFFFAFAFA, widthDp = 360, heightDp = 820)
@Composable
private fun GhostDuelOpponentPickerNoMatchesPreview() {
    RepMateTheme(darkTheme = false) {
        GhostDuelOpponentPicker(friends = emptyList(), onFriendPicked = {}, onDismissRequest = {})
    }
}
