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

data class LeaderboardUiState(
    val entries: List<LeaderboardEntry> = emptyList(),
    /** True when the query failed; [entries] is then empty. */
    val isUnavailable: Boolean = false,
)

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val leaderboardRepository: LeaderboardRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            leaderboardRepository.observeTop(20).collect { load ->
                _uiState.value =
                    when (load) {
                        is LeaderboardLoad.Loaded -> LeaderboardUiState(entries = load.entries)
                        is LeaderboardLoad.Failed -> {
                            Log.w("RepMateLeaderboard", "leaderboard unavailable on Leaderboard screen", load.cause)
                            LeaderboardUiState(isUnavailable = true)
                        }
                    }
            }
        }
    }
}