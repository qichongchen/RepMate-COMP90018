package com.repmate.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FriendsUiState(
    val friends: List<Friend> = emptyList(),
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

    fun addFriend(userId: String) {
        if (userId.isBlank()) {
            _uiState.update {
                it.copy(message = "Enter a user ID")
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

            val result = friendRepository.addFriend(userId)

            _uiState.update {
                it.copy(
                    isAdding = false,
                    message = result.fold(
                        onSuccess = { "Friend added" },
                        onFailure = { error ->
                            error.message ?: "Could not add friend"
                        },
                    ),
                )
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