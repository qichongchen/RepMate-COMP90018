package com.repmate.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRequest
import com.repmate.data.repo.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.catch

data class FriendsUiState(
    val friends: List<Friend> = emptyList(),
    val friendRequests: List<FriendRequest> = emptyList(),
    val isLoading: Boolean = true,
    val isAdding: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    init {
        observeFriends()
        observeFriendRequests()
    }

    private fun observeFriends() {
        viewModelScope.launch {
            friendRepository.observeFriends().collect { friends ->
                _uiState.update {
                    it.copy(
                        friends = friends,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun observeFriendRequests() {
        viewModelScope.launch {
            friendRepository.observeFriendRequests()
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            message = error.message
                                ?: "Could not load friend requests"
                        )
                    }
                }
                .collect { requests ->
                    _uiState.update {
                        it.copy(friendRequests = requests)
                    }
                }
        }
    }

    fun sendFriendRequest(displayName: String) {
        if (displayName.isBlank()) {
            _uiState.update {
                it.copy(message = "Enter a display name")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAdding = true,
                    message = null,
                )
            }

            val result = friendRepository.sendFriendRequest(displayName)

            _uiState.update {
                it.copy(
                    isAdding = false,
                    message = result.fold(
                        onSuccess = { "Friend request sent" },
                        onFailure = { error ->
                            error.message ?: "Could not send friend request"
                        },
                    ),
                )
            }
        }
    }

    fun acceptFriendRequest(userId: String) {
        viewModelScope.launch {
            val result = friendRepository.acceptFriendRequest(userId)

            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        message = result.exceptionOrNull()?.message
                            ?: "Could not accept friend request"
                    )
                }
            }
        }
    }

    fun rejectFriendRequest(userId: String) {
        viewModelScope.launch {
            val result = friendRepository.rejectFriendRequest(userId)

            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        message = result.exceptionOrNull()?.message
                            ?: "Could not reject friend request"
                    )
                }
            }
        }
    }

    fun removeFriend(userId: String) {
        viewModelScope.launch {
            val result = friendRepository.removeFriend(userId)

            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        message = result.exceptionOrNull()?.message
                            ?: "Could not remove friend"
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update {
            it.copy(message = null)
        }
    }
}