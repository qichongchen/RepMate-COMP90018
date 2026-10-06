package com.repmate.ui.friends

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.data.repo.Friend
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var displayName by remember { mutableStateOf("") }
    var showAddFriendDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect

        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    if (showAddFriendDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddFriendDialog = false
                displayName = ""
            },
            title = {
                Text("Add Friend")
            },
            text = {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    placeholder = { Text("Enter a display name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.sendFriendRequest(displayName)
                        showAddFriendDialog = false
                        displayName = ""
                    },
                    enabled = displayName.isNotBlank() && !uiState.isAdding,
                ) {
                    Text("Send Request")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddFriendDialog = false
                        displayName = ""
                    },
                ) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Friends") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddFriendDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add friend",
                        )
                    }
                },
            )
        },
        snackbarHost = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                SnackbarHost(hostState = snackbarHostState)
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            if (uiState.friendRequests.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Friend Requests (${uiState.friendRequests.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                items(
                    items = uiState.friendRequests,
                    key = { "request_${it.userId}" },
                ) { request ->
                    FriendRequestRow(
                        displayName = request.displayName,
                        onAcceptClick = {
                            viewModel.acceptFriendRequest(request.userId)
                        },
                        onRejectClick = {
                            viewModel.rejectFriendRequest(request.userId)
                        },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Your Friends",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (!uiState.isLoading) {
                        Text(
                            text = "${uiState.friends.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                uiState.friends.isEmpty() -> {
                    item {
                        Text("You haven't added any friends yet.")
                    }
                }

                else -> {
                    items(
                        items = uiState.friends,
                        key = { "friend_${it.userId}" },
                    ) { friend ->
                        FriendRow(
                            friend = friend,
                            onRemoveClick = {
                                viewModel.removeFriend(friend.userId)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = friend.displayName
                            .firstOrNull()
                            ?.uppercase()
                            ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = friend.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = "Friend",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Friend options",
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("Remove friend")
                        },
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
    displayName: String,
    onAcceptClick: () -> Unit,
    onRejectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayName,
            modifier = Modifier.weight(1f),
        )

        TextButton(onClick = onRejectClick) {
            Text("Reject")
        }

        Button(onClick = onAcceptClick) {
            Text("Accept")
        }
    }
}