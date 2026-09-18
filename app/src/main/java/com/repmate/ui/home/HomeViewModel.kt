package com.repmate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.repmate.data.repo.LeaderboardRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import com.repmate.ui.auth.accountDisplayFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Everything [HomeScreen] renders in and below its top bar: the avatar initial, today's date
 * label, the last session summary, the leaderboard top 3, and the ghost duel banner.
 */
data class HomeUiState(
    val todayLabel: String = "",
    val avatarInitial: String? = null,
    val lastSession: LastSessionUi? = null,
    val leaderboardTop3: List<LeaderboardEntryUi> = emptyList(),
    val ghostDuelOpponentName: String? = null,
)

data class LastSessionUi(
    val exercise: ExerciseType,
    val repCount: Int,
    val averageScore: Float,
)

data class LeaderboardEntryUi(
    val rank: Int,
    val name: String,
    val points: Int,
    val isCurrentUser: Boolean,
)

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    private val firebaseAuth: FirebaseAuth,
    private val sessionRepository: SessionRepository,
    private val leaderboardRepository: LeaderboardRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildInitialUiState(firebaseAuth))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.recent(limit = 1).collect { sessions ->
                _uiState.update {
                    it.copy(
                        lastSession = sessions.firstOrNull()?.toLastSessionUi()
                    )
                }
            }
        }

        viewModelScope.launch {
            leaderboardRepository.topPlayers(3).collect { entries ->
                val currentUserId = firebaseAuth.currentUser?.uid

                _uiState.update {
                    it.copy(
                        leaderboardTop3 =
                            entries.mapIndexed { index, entry ->
                                LeaderboardEntryUi(
                                    rank = index + 1,
                                    name = entry.displayName,
                                    points = entry.points,
                                    isCurrentUser = entry.userId == currentUserId,
                                )
                            }
                    )
                }
            }
        }
    }
}

private fun buildInitialUiState(firebaseAuth: FirebaseAuth): HomeUiState =
    HomeUiState(
        todayLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
        avatarInitial = accountDisplayFor(firebaseAuth).avatarInitial,
        lastSession = null,
        leaderboardTop3 = emptyList(),
        ghostDuelOpponentName = "Priya",
    )

private fun WorkoutSession.toLastSessionUi(): LastSessionUi =
    LastSessionUi(
        exercise = exercise,
        repCount = reps.size,
        averageScore =
            if (reps.isEmpty()) {
                0f
            } else {
                reps.map { it.score }.average().toFloat()
            },
    )