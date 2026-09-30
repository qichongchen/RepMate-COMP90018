package com.repmate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import com.repmate.safety.CheckInScheduler
import com.repmate.safety.SafetyCheckInPreferences
import com.repmate.ui.components.displayLabel
import com.repmate.ui.components.hasFormScoring
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** True when opened as the summary of a workout that just ended, false when opened from History. Set before the session loads so the header never flashes the History layout. */
    val postWorkout: Boolean = false,
    /** False when the rep scores are placeholders (see [hasFormScoring]); the screen shows "not scored yet" instead of them. */
    val hasFormScoring: Boolean = true,
    /** Minutes until the safety check-in asks "are you OK"; null when the note should not show (feature off, or not post-workout). */
    val safetyCheckInMinutes: Long? = null,
)

/**
 * Backs [SessionDetailScreen]. Resolves the `sessionId` nav argument into a [SessionDetailUiState]
 * exactly once via [onSessionId], the same "applied once, from whatever the nav route parsed"
 * pattern [com.repmate.ui.motionreplay.MotionReplayViewModel] uses for its own `sessionId` --
 * there is no `SavedStateHandle` route-argument idiom elsewhere in this app to follow instead.
 *
 * Serves both History's plain detail and the post-workout summary; the only differences are the
 * `postWorkout` flag and the safety check-in note, which is read from [SafetyCheckInPreferences]
 * only in post-workout mode (it describes a check-in that was just scheduled, so it is meaningless
 * when browsing old sessions).
 */
@HiltViewModel
class SessionDetailViewModel
@Inject
constructor(
    private val sessionRepository: SessionRepository,
    private val safetyCheckInPreferences: SafetyCheckInPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    private var initializedSessionId: String? = null

    /**
     * Called once by [SessionDetailScreen] with the session id and mode parsed from the nav route.
     *
     * @param postWorkout true when the route came from a just-finished workout.
     */
    fun onSessionId(
        sessionId: String,
        postWorkout: Boolean = false,
    ) {
        if (initializedSessionId != null) return
        initializedSessionId = sessionId
        _uiState.update { it.copy(postWorkout = postWorkout) }

        viewModelScope.launch {
            val session = sessionRepository.getById(sessionId)
            val safetyMinutes = if (postWorkout) safetyCheckInMinutesOrNull() else null
            _uiState.value =
                session?.toSessionDetailUiState(postWorkout, safetyMinutes)
                    ?: SessionDetailUiState(isLoading = false, notFound = true, postWorkout = postWorkout)
        }
    }

    /**
     * The check-in delay in minutes if the user has Safety check-in on, else null. A failed
     * preferences read is treated as "off" (Golden Rule 7): a missing reassurance note must never
     * cost the user their summary.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun safetyCheckInMinutesOrNull(): Long? =
        try {
            if (safetyCheckInPreferences.isEnabledSnapshot()) CheckInScheduler.DEFAULT_CHECK_IN_DELAY.inWholeMinutes else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}

/**
 * Maps a saved session to what Session Detail renders. Pure (no Android, no I/O) so it is unit
 * tested directly; see `SessionDetailMapperTest`.
 *
 * @param safetyCheckInMinutes already resolved by the caller; ignored outside post-workout mode so
 *   History can never show the note.
 */
internal fun WorkoutSession.toSessionDetailUiState(
    postWorkout: Boolean = false,
    safetyCheckInMinutes: Long? = null,
): SessionDetailUiState =
    SessionDetailUiState(
        exercise = exercise.displayLabel(),
        dateLabel = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).format(HISTORY_DATE_FORMATTER),
        durationLabel = durationLabel(),
        repCount = reps.size,
        averageScore = averageScore(),
        reps = reps,
        isLoading = false,
        notFound = false,
        postWorkout = postWorkout,
        hasFormScoring = exercise.hasFormScoring,
        safetyCheckInMinutes = safetyCheckInMinutes.takeIf { postWorkout },
    )
