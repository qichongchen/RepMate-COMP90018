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

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect

        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
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
            item {
                Text("Add a friend using their display name")
            }

            item {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Button(
                    onClick = {
                        viewModel.sendFriendRequest(displayName)
                        displayName = ""
                    },
                    enabled = displayName.isNotBlank() && !uiState.isAdding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isAdding) {
                        CircularProgressIndicator()
                    } else {
                        Text("Send request")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Friend requests")
            }

            if (uiState.friendRequests.isEmpty()) {
                item {
                    Text("No pending friend requests.")
                }
            } else {
                items(
                    items = uiState.friendRequests,
                    key = { request -> request.userId },
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
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Your friends")
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
                        key = { friend -> friend.userId },
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
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = friend.displayName,
            modifier = Modifier.weight(1f),
        )

        TextButton(onClick = onRemoveClick) {
            Text("Remove")
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