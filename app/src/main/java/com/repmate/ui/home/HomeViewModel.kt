package com.repmate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
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
    /** Null means "show the generic person-silhouette fallback", not "loading" -- see [com.repmate.ui.auth.accountDisplayFor]. */
    val avatarInitial: String? = null,
    val lastSession: LastSessionUi? = null,
    val leaderboardTop3: List<LeaderboardEntryUi> = emptyList(),
    val ghostDuelOpponentName: String? = null,
)

/** The pieces of a [WorkoutSession] Home actually shows -- computed from one via [toLastSessionUi]. */
data class LastSessionUi(
    val exercise: ExerciseType,
    val repCount: Int,
    val averageScore: Float,
)

/** One row of the leaderboard: rank, display name, points. [isCurrentUser] bolds the row. */
data class LeaderboardEntryUi(
    val rank: Int,
    val name: String,
    val points: Int,
    val isCurrentUser: Boolean,
)

/**
 * Backs [HomeScreen]'s display state. The exercise-chip tap decision (calibrated or not) is
 * deliberately not here -- see [CalibrationGateViewModel] -- since that's a navigation decision
 * made once, at the `RepMateNavGraph` level, not a piece of this screen's own display state.
 *
 * TODO(cloud): [leaderboardTop3] is fake data, not a live query -- there is no Firestore
 * leaderboard snapshot to read from yet. It's a call-site-only change once ready: replace the
 * hardcoded [LeaderboardEntryUi] list in [buildInitialUiState] with a real snapshot mapped to the
 * same shape.
 *
 * [lastSession] is real: it's collected from [SessionRepository.recent] (limit 1) and mapped
 * through [toLastSessionUi], so the card updates the moment a workout is saved, with no manual
 * refresh. Null covers both "still loading" and "no sessions yet" -- [HomeScreen] already treats
 * a null [HomeUiState.lastSession] as "show the empty state", which is also the correct display
 * for a brand-new user who hasn't finished a workout yet.
 *
 * [HomeUiState.avatarInitial], unlike the rest of this state, is real: it reads the signed-in
 * [FirebaseAuth] user directly, the same way [com.repmate.ui.auth.AuthViewModel] and every other
 * ViewModel in this app that needs the current user does.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(buildInitialUiState(firebaseAuth))
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                sessionRepository.recent(limit = 1).collect { sessions ->
                    _uiState.update { it.copy(lastSession = sessions.firstOrNull()?.toLastSessionUi()) }
                }
            }
        }
    }

private fun buildInitialUiState(firebaseAuth: FirebaseAuth): HomeUiState =
    HomeUiState(
        todayLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
        avatarInitial = accountDisplayFor(firebaseAuth).avatarInitial,
        lastSession = null,
        leaderboardTop3 =
            listOf(
                LeaderboardEntryUi(rank = 1, name = "Priya", points = 1420, isCurrentUser = false),
                LeaderboardEntryUi(rank = 2, name = "You", points = 1305, isCurrentUser = true),
                LeaderboardEntryUi(rank = 3, name = "Marcus", points = 1260, isCurrentUser = false),
            ),
        ghostDuelOpponentName = "Priya",
    )

private fun WorkoutSession.toLastSessionUi(): LastSessionUi =
    LastSessionUi(
        exercise = exercise,
        repCount = reps.size,
        averageScore = if (reps.isEmpty()) 0f else reps.map { it.score }.average().toFloat(),
    )
