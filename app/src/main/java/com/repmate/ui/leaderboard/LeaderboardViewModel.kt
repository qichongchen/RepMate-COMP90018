package com.repmate.ui.leaderboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.LeaderboardEntry
import com.repmate.data.repo.LeaderboardLoad
import com.repmate.data.repo.LeaderboardRepository
import com.repmate.data.repo.observeTop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.repmate.data.repo.FriendRepository
import com.repmate.data.repo.observeFriends



enum class LeaderboardType {
    FRIENDS,
    GLOBAL,
}
data class LeaderboardUiState(
    val entries: List<LeaderboardEntry> = emptyList(),
    val selectedType: LeaderboardType = LeaderboardType.FRIENDS,
    val isUnavailable: Boolean = false,
)

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val leaderboardRepository: LeaderboardRepository,
    private val friendRepository: FriendRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            friendRepository.observeFriends().collect { friends ->
                val friendUserIds = friends.map { it.userId }

                leaderboardRepository
                    .observeFriends(friendUserIds)
                    .collect { load ->
                        _uiState.value =
                            when (load) {
                                is LeaderboardLoad.Loaded -> {
                                    _uiState.value.copy(
                                        entries = load.entries,
                                        isUnavailable = false,
                                    )
                                }

                                is LeaderboardLoad.Failed -> {
                                    Log.w(
                                        "RepMateLeaderboard",
                                        "friends leaderboard unavailable",
                                        load.cause
                                    )

                                    _uiState.value.copy(
                                        entries = emptyList(),
                                        isUnavailable = true,
                                    )
                                }
                            }
                    }
            }
        }
    }

    fun selectLeaderboard(type: LeaderboardType) {
        if (_uiState.value.selectedType == type) return

        _uiState.value = _uiState.value.copy(
            selectedType = type,
            isUnavailable = false,
        )
    }
}