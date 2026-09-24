package com.repmate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import com.repmate.ui.components.displayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class SessionDetailUiState(
    val exercise: String = "",
    val dateLabel: String = "",
    val durationLabel: String? = null,
    val repCount: Int = 0,
    val averageScore: Float = 0f,
    val reps: List<RepScore> = emptyList(),
    val isLoading: Boolean = true,
    /** True when [SessionRepository.getById] returned null -- the session was deleted, or doesn't belong to the current user. */
    val notFound: Boolean = false,
)

/**
 * Backs [SessionDetailScreen]. Resolves the `sessionId` nav argument into a [SessionDetailUiState]
 * exactly once via [onSessionId], the same "applied once, from whatever the nav route parsed"
 * pattern [com.repmate.ui.motionreplay.MotionReplayViewModel] uses for its own `sessionId` --
 * there is no `SavedStateHandle` route-argument idiom elsewhere in this app to follow instead.
 */
@HiltViewModel
class SessionDetailViewModel
@Inject
constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    private var initializedSessionId: String? = null

    /** Called once by [SessionDetailScreen] with the real session id parsed from the nav route. */
    fun onSessionId(sessionId: String) {
        if (initializedSessionId != null) return
        initializedSessionId = sessionId

        viewModelScope.launch {
            val session = sessionRepository.getById(sessionId)
            _uiState.value = session?.toSessionDetailUiState() ?: SessionDetailUiState(isLoading = false, notFound = true)
        }
    }
}

private fun WorkoutSession.toSessionDetailUiState(): SessionDetailUiState =
    SessionDetailUiState(
        exercise = exercise.displayLabel(),
        dateLabel = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).format(HISTORY_DATE_FORMATTER),
        durationLabel = durationLabel(),
        repCount = reps.size,
        averageScore = averageScore(),
        reps = reps,
        isLoading = false,
        notFound = false,
    )
