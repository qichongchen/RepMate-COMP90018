package com.repmate.ui.home

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Everything [HomeScreen] renders in and below its top bar: the avatar initial, today's date
 * label, the last session summary, the leaderboard top 3, and the ghost duel banner.
 */
data class HomeUiState(
    val todayLabel: String = "",
    /** Null means "show the generic person-silhouette fallback", not "loading" -- see [computeAvatarInitial]. */
    val avatarInitial: String? = null,
    val lastSession: LastSessionUi? = null,
    val leaderboardTop3: List<LeaderboardEntryUi> = emptyList(),
    val ghostDuelOpponentName: String? = null,
)

/**
 * The pieces of a [WorkoutSession] Home actually shows -- computed from one via [toLastSessionUi],
 * not a replacement for it, so wiring in the real `SessionRepository` later is a ViewModel-only
 * change: map whatever `SessionRepository.recent(1)` returns through that same function instead
 * of the fake session below.
 */
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
 * TODO(data.local, cloud): [lastSession] and [leaderboardTop3] are fake data shaped like the real
 * thing (a real [WorkoutSession] with real [RepScore]s, mapped the same way the real data would
 * be), not a live query -- `SessionRepository.recent(1)` already exists and is Hilt-wired (see
 * `RoomSessionRepository`) but this screen isn't reading from it yet, and there is no Firestore
 * leaderboard snapshot to read from at all. Both are call-site-only changes once ready: replace
 * [buildInitialUiState]'s fake [WorkoutSession] with the repository's real one, and its hardcoded
 * [LeaderboardEntryUi] list with a real snapshot mapped to the same shape.
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
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(buildInitialUiState(firebaseAuth))
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    }

private fun buildInitialUiState(firebaseAuth: FirebaseAuth): HomeUiState {
    val fakeSession =
        WorkoutSession(
            id = "preview-session",
            exercise = ExerciseType.SQUAT,
            startedAt = System.currentTimeMillis(),
            reps =
                List(12) { index ->
                    RepScore(
                        repIndex = index,
                        score = 8.2f,
                        tempoSeconds = 2.1f,
                        rangePercent = 92,
                        pauseSeconds = 0.4f,
                        reasons = listOf("good depth"),
                    )
                },
        )

    return HomeUiState(
        todayLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
        avatarInitial = computeAvatarInitial(firebaseAuth),
        lastSession = fakeSession.toLastSessionUi(),
        leaderboardTop3 =
            listOf(
                LeaderboardEntryUi(rank = 1, name = "Priya", points = 1420, isCurrentUser = false),
                LeaderboardEntryUi(rank = 2, name = "You", points = 1305, isCurrentUser = true),
                LeaderboardEntryUi(rank = 3, name = "Marcus", points = 1260, isCurrentUser = false),
            ),
        ghostDuelOpponentName = "Priya",
    )
}

/**
 * Google sign-ups have a `displayName`; email/password sign-ups don't (that flow deliberately
 * doesn't collect a name -- see `SignUpScreen`'s NOTE on that), so this falls back to the first
 * letter of the email instead. Guests have neither, so this falls back to null -- the generic
 * person-silhouette icon, not a made-up letter for an account with no name or email at all.
 */
private fun computeAvatarInitial(firebaseAuth: FirebaseAuth): String? {
    val user = firebaseAuth.currentUser
    val displayName = user?.displayName
    if (!displayName.isNullOrBlank()) {
        return displayName.first().uppercase()
    }
    val email = user?.email
    if (!email.isNullOrBlank()) {
        return email.first().uppercase()
    }
    return null
}

private fun WorkoutSession.toLastSessionUi(): LastSessionUi =
    LastSessionUi(
        exercise = exercise,
        repCount = reps.size,
        averageScore = if (reps.isEmpty()) 0f else reps.map { it.score }.average().toFloat(),
    )
