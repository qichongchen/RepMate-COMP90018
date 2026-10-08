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
import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.repo.FriendRepository
import com.repmate.data.repo.observeFriends
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest



enum class LeaderboardType {
    FRIENDS,
    GLOBAL,
}
data class LeaderboardUiState(
    val entries: List<LeaderboardEntry> = emptyList(),
    val selectedType: LeaderboardType = LeaderboardType.FRIENDS,
    val isUnavailable: Boolean = false,
    /**
     * The signed-in user, so the screen can mark their own row. Null when signed out, which only
     * happens in the moment before the nav graph sends them to Welcome.
     *
     * A leaderboard you cannot find yourself on is hard to read, and the friends board now
     * includes the signed-in user (see [LeaderboardViewModel.loadFriendsLeaderboard]), so without
     * this their row is indistinguishable from everyone else's.
     */
    val currentUserId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val leaderboardRepository: LeaderboardRepository,
    private val friendRepository: FriendRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()
    private var leaderboardJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.getCurrentUserId())
        loadFriendsLeaderboard()
    }

    private fun loadFriendsLeaderboard() {
        leaderboardJob?.cancel()

        leaderboardJob = viewModelScope.launch {
            // flatMapLatest, not a nested collect: the inner flow for a non-empty friends list
            // never completes, so collecting it inside the outer collect meant the lambda never
            // returned and every later friends-list change was ignored -- adding a second friend
            // showed nothing until the screen was left and reopened.
            friendRepository.observeFriends()
                .flatMapLatest { friends ->
                    // The signed-in user is included so they can see their own standing among
                    // their friends; a leaderboard you are absent from is hard to read.
                    val userIds =
                        (friends.map { it.userId } + listOfNotNull(authRepository.getCurrentUserId())).distinct()
                    leaderboardRepository.observeFriends(userIds)
                }
                .collect { load ->
                    _uiState.value =
                        when (load) {
                            is LeaderboardLoad.Loaded ->
                                _uiState.value.copy(
                                    entries = load.entries,
                                    selectedType = LeaderboardType.FRIENDS,
                                    isUnavailable = false,
                                )

                            is LeaderboardLoad.Failed -> {
                                Log.w("RepMateLeaderboard", "friends leaderboard unavailable", load.cause)
                                _uiState.value.copy(
                                    entries = emptyList(),
                                    selectedType = LeaderboardType.FRIENDS,
                                    isUnavailable = true,
                                )
                            }
                        }
                }
        }
    }

    private fun loadGlobalLeaderboard() {
        leaderboardJob?.cancel()

        leaderboardJob = viewModelScope.launch {
            leaderboardRepository.observeTop(20).collect { load ->
                _uiState.value =
                    when (load) {
                        is LeaderboardLoad.Loaded -> {
                            _uiState.value.copy(
                                entries = load.entries,
                                selectedType = LeaderboardType.GLOBAL,
                                isUnavailable = false,
                            )
                        }

                        is LeaderboardLoad.Failed -> {
                            Log.w(
                                "RepMateLeaderboard",
                                "global leaderboard unavailable",
                                load.cause
                            )

                            _uiState.value.copy(
                                entries = emptyList(),
                                selectedType = LeaderboardType.GLOBAL,
                                isUnavailable = true,
                            )
                        }
                    }
            }
        }
    }

    fun selectLeaderboard(type: LeaderboardType) {
        if (_uiState.value.selectedType == type) return

        _uiState.value = _uiState.value.copy(
            selectedType = type,
            entries = emptyList(),
            isUnavailable = false,
        )

        when (type) {
            LeaderboardType.FRIENDS -> loadFriendsLeaderboard()
            LeaderboardType.GLOBAL -> loadGlobalLeaderboard()
        }
    }
}