package com.repmate.ui.ghostduel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.repo.Friend
import com.repmate.data.repo.FriendRepository
import com.repmate.engine.ExerciseType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One side of the comparison card: a best-ever score and the rep count it was set with. */
data class GhostDuelBestScore(val score: Float, val reps: Int)

data class GhostDuelUiState(
    val friends: List<Friend> = emptyList(),
    val selectedFriendId: String? = null,
    val selectedExercise: ExerciseType = ExerciseType.SQUAT,
    val yourBest: GhostDuelBestScore? = null,
    val friendsBest: GhostDuelBestScore? = null,
    val isLoading: Boolean = true,
)

/**
 * Backs [GhostDuelScreen]: the opponent and exercise picks live here, updated by
 * [onFriendSelected]/[onExerciseSelected] as the user taps rows on the screen. The actual
 * best-score comparison isn't backed by anything yet -- see the `TODO` below -- so
 * [GhostDuelUiState.yourBest]/[GhostDuelUiState.friendsBest] stay `null` for every build using
 * this class, and the screen renders its "not available yet" box for both sides accordingly.
 */
@HiltViewModel
class GhostDuelViewModel
@Inject
constructor(
    private val friendRepository: FriendRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GhostDuelUiState())
    val uiState: StateFlow<GhostDuelUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            friendRepository.observeFriends()
                // FirestoreFriendRepository's callbackFlow closes with an exception on a listener
                // failure or a missing signed-in user, which would otherwise crash this coroutine
                // uncaught -- degrade to an empty friend list instead (Golden Rule 7), same
                // Log.w-and-fall-back shape LeaderboardViewModel uses for its own failed query.
                .catch { error ->
                    Log.w("RepMateGhostDuel", "friends unavailable on Ghost Duel screen", error)
                    emit(emptyList())
                }
                .collect { friends ->
                    _uiState.update { state ->
                        state.copy(
                            friends = friends,
                            // Auto-select the first friend the moment the list first arrives
                            // non-empty, matching the wireframe's default-selected state -- but only
                            // when nothing is selected yet, so a later emission (e.g. a friend
                            // removed elsewhere) can't stomp a pick the user already made.
                            selectedFriendId = state.selectedFriendId ?: friends.firstOrNull()?.userId,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    fun onFriendSelected(friendId: String) {
        _uiState.update { it.copy(selectedFriendId = friendId) }
    }

    fun onExerciseSelected(exercise: ExerciseType) {
        _uiState.update { it.copy(selectedExercise = exercise) }
    }

    // TODO(Lisa): best score for a given user + exercise doesn't exist anywhere yet, Room only
    // holds the signed-in user's own sessions so a friend's best needs a Firestore query regardless.
    // Wire the real lookup in here once it exists, for both currentUserId (from AuthRepository) and
    // the selected friend's id, and drop these two nulls.
}
