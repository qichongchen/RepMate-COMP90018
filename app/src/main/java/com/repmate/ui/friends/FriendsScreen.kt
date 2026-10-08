package com.repmate.ui.friends

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRequest
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.RepMateButtonVariant
import com.repmate.ui.components.RepMateCard
import com.repmate.ui.components.RepMateTopBar
import com.repmate.ui.theme.RepMateTheme

@Composable
fun FriendsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    FriendsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onSendRequest = viewModel::sendFriendRequest,
        onAcceptRequest = viewModel::acceptFriendRequest,
        onRejectRequest = viewModel::rejectFriendRequest,
        onRemoveFriend = viewModel::removeFriend,
        modifier = modifier,
    )
}

/**
 * Friends, stateless so every state can be previewed.
 *
 * This screen was the one place in the app built out of stock Material parts -- a `Scaffold` with
 * a `TopAppBar`, and `primaryContainer`/`surfaceContainerLow` for its colours. The theme defines
 * neither of those two colours, so they fell back to Material's default purple and read as
 * another app's screen. It now uses [RepMateTopBar] and [RepMateCard] like the rest.
 */
@Composable
internal fun FriendsContent(
    uiState: FriendsUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onSendRequest: (String) -> Unit,
    onAcceptRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddFriendDialog by remember { mutableStateOf(false) }

    if (showAddFriendDialog) {
        AddFriendDialog(
            isSending = uiState.isAdding,
            onDismiss = { showAddFriendDialog = false },
            onSend = { name ->
                onSendRequest(name)
                showAddFriendDialog = false
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            RepMateTopBar(title = "Friends", onBackClick = onBackClick) {
                IconButton(
                    onClick = { showAddFriendDialog = true },
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add friend",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (uiState.friendRequests.isNotEmpty()) {
                    item {
                        SectionLabel("requests (${uiState.friendRequests.size})")
                    }
                    items(uiState.friendRequests, key = { "request_${it.userId}" }) { request ->
                        FriendRequestRow(
                            request = request,
                            onAcceptClick = { onAcceptRequest(request.userId) },
                            onRejectClick = { onRejectRequest(request.userId) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(6.dp)) }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel("your friends")
                        if (!uiState.isLoading) {
                            Text(
                                text = "${uiState.friends.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                when {
                    uiState.isLoading ->
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }

                    uiState.friends.isEmpty() ->
                        item {
                            Text(
                                text = "No friends yet. Add someone by their display name to compare scores and race their best set.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }

                    else ->
                        items(uiState.friends, key = { "friend_${it.userId}" }) { friend ->
                            FriendRow(friend = friend, onRemoveClick = { onRemoveFriend(friend.userId) })
                        }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }

        // Bottom, where a snackbar belongs -- it used to be centred over the whole screen.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 6.dp),
    )
}

/** An initial in a circle, the same shape the leaderboard uses for its rows. */
@Composable
private fun FriendAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayName.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    RepMateCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FriendAvatar(displayName = friend.displayName)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = friend.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options for ${friend.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove friend") },
                        onClick = {
                            menuExpanded = false
                            onRemoveClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRequestRow(
    request: FriendRequest,
    onAcceptClick: () -> Unit,
    onRejectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RepMateCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FriendAvatar(displayName = request.displayName)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "wants to be friends",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RepMateButton(
                text = "Accept",
                onClick = onAcceptClick,
                modifier = Modifier.weight(1f),
            )
            RepMateButton(
                text = "Decline",
                onClick = onRejectClick,
                variant = RepMateButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddFriendDialog(
    isSending: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Add a friend",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter their display name exactly as they set it. They have to accept before you are friends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RepMateButton(
                text = "Send request",
                onClick = { onSend(displayName) },
                enabled = displayName.isNotBlank() && !isSending,
            )
        },
        dismissButton = {
            RepMateButton(text = "Cancel", onClick = onDismiss, variant = RepMateButtonVariant.Ghost)
        },
    )
}

// --- previews ------------------------------------------------------------------------------

private val PREVIEW_STATE =
    FriendsUiState(
        friends =
            listOf(
                Friend(userId = "u1", displayName = "Mohit"),
                Friend(userId = "u2", displayName = "A Very Long Display Name"),
            ),
        friendRequests = listOf(FriendRequest(userId = "u3", displayName = "Priya")),
        isLoading = false,
    )

@Preview(name = "Friends - dark", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun FriendsDarkPreview() {
    RepMateTheme(darkTheme = true) {
        FriendsContent(
            uiState = PREVIEW_STATE,
            snackbarHostState = remember { SnackbarHostState() },
            onBackClick = {},
            onSendRequest = {},
            onAcceptRequest = {},
            onRejectRequest = {},
            onRemoveFriend = {},
        )
    }
}

@Preview(name = "Friends - light", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun FriendsLightPreview() {
    RepMateTheme(darkTheme = false) {
        FriendsContent(
            uiState = PREVIEW_STATE,
            snackbarHostState = remember { SnackbarHostState() },
            onBackClick = {},
            onSendRequest = {},
            onAcceptRequest = {},
            onRejectRequest = {},
            onRemoveFriend = {},
        )
    }
}

@Preview(name = "Friends - empty", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun FriendsEmptyPreview() {
    RepMateTheme(darkTheme = true) {
        FriendsContent(
            uiState = FriendsUiState(isLoading = false),
            snackbarHostState = remember { SnackbarHostState() },
            onBackClick = {},
            onSendRequest = {},
            onAcceptRequest = {},
            onRejectRequest = {},
            onRemoveFriend = {},
        )
    }
}
