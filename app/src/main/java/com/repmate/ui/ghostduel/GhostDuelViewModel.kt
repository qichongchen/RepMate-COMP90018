package com.repmate.ui.ghostduel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.cloud.FirestoreGhostScoreDataSource
import com.repmate.data.repo.SessionRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

private const val TAG = "RepMateGhostDuel"

/**
 * One side of the comparison card: a best-ever session
 * average score and the rep count it was achieved with.
 */
data class GhostDuelBestScore(val score: Float, val reps: Int)

data class GhostDuelUiState(
    val friends: List<Friend> = emptyList(),
    val selectedFriendId: String? = null,
    val selectedExercise: ExerciseType = ExerciseType.SQUAT,
    val yourBest: GhostDuelBestScore? = null,
    val friendsBest: GhostDuelBestScore? = null,
    val isLoading: Boolean = true,
    val isScoreLoading: Boolean = false,
    val yourScoreError: Boolean = false,
    val friendScoreError: Boolean = false,
)

/**
 * Backs [GhostDuelScreen].
 *
 * Observes the current user's friends and manages the selected
 * opponent and exercise.
 *
 * Loads the current user's best session score from
 * [SessionRepository] and the selected friend's published
 * best score from [FirestoreGhostScoreDataSource].
 *
 * Refreshes the comparison whenever the selected friend
 * or exercise changes.
 *
 * Cancels outdated requests and uses a request version to
 * prevent stale results from overwriting the current selection.
 */
@HiltViewModel
class GhostDuelViewModel
@Inject
constructor(
    private val friendRepository: FriendRepository,
    private val sessionRepository: SessionRepository,
    private val ghostScoreDataSource: FirestoreGhostScoreDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GhostDuelUiState())
    val uiState: StateFlow<GhostDuelUiState> = _uiState.asStateFlow()

    private var scoreJob: Job? = null

    // Incremented whenever the selected comparison changes.
    private var scoreRequestVersion = 0L

    init {
        viewModelScope.launch {
            friendRepository.observeFriends()
                .catch { error ->
                    if (error is CancellationException) {
                        throw error
                    }

                    Log.w(
                        TAG,
                        "Friends unavailable on Ghost Duel screen",
                        error
                    )

                    emit(emptyList())
                }
                .collect { friends ->
                    val previousFriendId =
                        _uiState.value.selectedFriendId

                    // Preserve the current selection if the friend
                    // still exists. Otherwise, select the first friend.
                    val selectedFriendId = previousFriendId
                        ?.takeIf { id ->
                            friends.any { it.userId == id }
                        }
                        ?: friends.firstOrNull()?.userId

                    _uiState.update { state ->
                        state.copy(
                            friends = friends,
                            selectedFriendId = selectedFriendId,
                            isLoading = false
                        )
                    }

                    // Load scores on the first friend emission or
                    // whenever the selected friend changes.
                    if (
                        previousFriendId != selectedFriendId ||
                        scoreJob == null
                    ) {
                        loadScores()
                    }
                }
        }
    }

    /**
     * Selects an opponent and refreshes the comparison.
     */
    fun onFriendSelected(friendId: String) {
        val state = _uiState.value

        if (state.selectedFriendId == friendId) {
            return
        }

        // Only allow selecting a friend in the current list.
        if (state.friends.none { it.userId == friendId }) {
            return
        }

        _uiState.update {
            it.copy(selectedFriendId = friendId)
        }

        loadScores()
    }

    /**
     * Selects an exercise and refreshes both scores.
     */
    fun onExerciseSelected(exercise: ExerciseType) {
        if (_uiState.value.selectedExercise == exercise) {
            return
        }

        _uiState.update {
            it.copy(selectedExercise = exercise)
        }

        loadScores()
    }

    /**
     * Loads the current user's local best score and the selected
     * friend's published Firestore score.
     *
     * Each request captures its friend and exercise selection.
     * Older requests are cancelled and cannot update the UI
     * after a newer request has started.
     */
    private fun loadScores() {
        // Invalidate older requests before cancelling their jobs.
        val requestVersion = ++scoreRequestVersion

        scoreJob?.cancel()
        scoreJob = null

        val friendId = _uiState.value.selectedFriendId
        val exercise = _uiState.value.selectedExercise

        // Clear the previous comparison immediately so the screen
        // cannot display scores for the wrong friend or exercise.
        _uiState.update {
            it.copy(
                yourBest = null,
                friendsBest = null,
                isScoreLoading = true,
                yourScoreError = false,
                friendScoreError = false
            )
        }

        scoreJob = viewModelScope.launch {
            try {
                // 1. Load the current user's best score from Room.
                val myScore = try {
                    sessionRepository.getMyBestScore(exercise)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to load own best score",
                        e
                    )

                    if (requestVersion == scoreRequestVersion) {
                        _uiState.update {
                            it.copy(yourScoreError = true)
                        }
                    }

                    null
                }

                // Ignore results belonging to an older selection.
                if (requestVersion != scoreRequestVersion) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        yourBest = myScore?.let { score ->
                            GhostDuelBestScore(
                                score = score.score,
                                reps = score.reps
                            )
                        }
                    )
                }

                // Friend Best is only loaded once a friend is selected.
                if (friendId == null) {
                    return@launch
                }

                // 2. Load the selected friend's published score.
                try {
                    val friendResult =
                        ghostScoreDataSource.getFriendBestScore(
                            friendUid = friendId,
                            exercise = exercise
                        )

                    if (requestVersion != scoreRequestVersion) {
                        return@launch
                    }

                    friendResult.fold(
                        onSuccess = { score ->
                            _uiState.update {
                                it.copy(
                                    friendsBest = score?.let { best ->
                                        GhostDuelBestScore(
                                            score = best.score,
                                            reps = best.reps
                                        )
                                    }
                                )
                            }
                        },
                        onFailure = { error ->
                            if (error is CancellationException) {
                                throw error
                            }

                            Log.w(
                                TAG,
                                "Failed to load friend's best score",
                                error
                            )

                            if (requestVersion == scoreRequestVersion) {
                                _uiState.update {
                                    it.copy(friendScoreError = true)
                                }
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Unexpected friend score query failure",
                        e
                    )

                    if (requestVersion == scoreRequestVersion) {
                        _uiState.update {
                            it.copy(friendScoreError = true)
                        }
                    }
                }
            } finally {
                // An old cancelled request must not change the
                // loading state of a newer request.
                if (requestVersion == scoreRequestVersion) {
                    _uiState.update {
                        it.copy(isScoreLoading = false)
                    }
                }
            }
        }
    }
}