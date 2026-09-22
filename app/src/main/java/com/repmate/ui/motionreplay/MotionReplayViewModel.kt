package com.repmate.ui.motionreplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.CalibrationRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.SessionReplayer
import com.repmate.engine.WorkoutSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wraps [MotionReplayUiState] with the one extra thing it structurally can't carry: the plain
 * [RepScore] list [MotionReplayScreen] needs for its "no replay data" state (STATE 3 in the
 * design canvas) -- [MotionReplayUiState.reps] is a `List<MotionReplayRepUi>`, and
 * [MotionReplayRepUi] has no case for a bare [RepScore], only [MotionReplayRepUi.Calibrated] /
 * [MotionReplayRepUi.Uncalibrated].
 *
 * [uiState] is `null` only for the one frame between the screen appearing and
 * [MotionReplayViewModel] resolving `sessionId`. [fallbackReps] is populated only when
 * `uiState?.isReplayAvailable` is `false`, and is empty (and unused) otherwise.
 */
data class MotionReplayScreenState(
    val uiState: MotionReplayUiState? = null,
    val fallbackReps: List<RepScore> = emptyList(),
)

/**
 * Backs [MotionReplayScreen]. Resolves the `sessionId` nav argument into a
 * [MotionReplayScreenState] exactly once, via [onSessionId], then only ever mutates
 * `uiState.currentIndex` from there (see [onPrevRepClicked]/[onNextRepClicked]).
 *
 * ## Why [SessionRepository.getById] only, for now
 * Getting a session back with its real [com.repmate.engine.MotionFrame]s right after a workout
 * just finished -- and wiring the actual capture of those frames in `LiveWorkoutViewModel` -- is
 * separate, ongoing work; this class deliberately does not touch `LiveWorkoutViewModel` and only
 * reads sessions back through [SessionRepository.getById]. Every session that path returns has
 * `frames = null` today (Room has no column for them yet, same as [SessionRepository.recent]), so
 * [MotionReplayUiState.isReplayAvailable] is `false` for every session right now -- expected, not
 * a bug. Nothing here needs to change once frame capture lands: [buildScreenState] already takes
 * the `frames != null` branch whenever it's true.
 *
 * ## Calibration profile lookup in [buildScreenState]
 * Now that `calibrationBand()` in `MotionReplayUiModels.kt` is a real implementation (PR #21
 * landed the missing [com.repmate.engine.CalibrationProfile.loudestSampleAmplitude] field), this
 * looks up the session's real profile via [CalibrationRepository] -- the same interface
 * `LiveWorkoutViewModel` already depends on -- and passes it through to both
 * [SessionReplayer.replay] and `toMotionReplayUiState`. A `null` result (exercise never
 * calibrated) is not an error: every rep still renders, just through
 * [MotionReplayRepUi.Uncalibrated] instead of [MotionReplayRepUi.Calibrated].
 */
@HiltViewModel
class MotionReplayViewModel
@Inject
constructor(
    private val sessionRepository: SessionRepository,
    private val calibrationRepository: CalibrationRepository,
) : ViewModel() {
    private val _screenState = MutableStateFlow(MotionReplayScreenState())
    val screenState: StateFlow<MotionReplayScreenState> = _screenState.asStateFlow()

    // Same "applied once, from whatever the nav route parsed" pattern as
    // LiveWorkoutViewModel.onExerciseType / CalibrationViewModel.onExerciseType.
    private var initializedSessionId: String? = null

    /** Called once by [MotionReplayScreen] with the real session id parsed from the nav route. */
    fun onSessionId(sessionId: String) {
        if (initializedSessionId != null) return
        initializedSessionId = sessionId

        viewModelScope.launch {
            val session = sessionRepository.getById(sessionId)
            _screenState.value = session?.let { buildScreenState(it) } ?: notFoundScreenState(sessionId)
        }
    }

    private suspend fun buildScreenState(session: WorkoutSession): MotionReplayScreenState {
        session.frames ?: return noReplayDataState(session)

        val profile = calibrationRepository.getProfile(session.exercise)
        val replayed = SessionReplayer().replay(session, profile = profile)
        return replayed?.toMotionReplayUiState(profile = profile)?.let { MotionReplayScreenState(uiState = it) }
            ?: noReplayDataState(session)
    }

    private fun noReplayDataState(session: WorkoutSession) =
        MotionReplayScreenState(
            uiState =
                MotionReplayUiState(
                    sessionId = session.id,
                    exercise = session.exercise,
                    reps = emptyList(),
                    currentIndex = 0,
                    isReplayAvailable = false,
                ),
            fallbackReps = session.reps,
        )

    /** [ExerciseType.SQUAT] is an arbitrary, unused default (Golden Rule 7): nothing reads it -- the empty state never shows an exercise name. */
    private fun notFoundScreenState(sessionId: String) =
        MotionReplayScreenState(
            uiState =
                MotionReplayUiState(
                    sessionId = sessionId,
                    exercise = ExerciseType.SQUAT,
                    reps = emptyList(),
                    currentIndex = 0,
                    isReplayAvailable = false,
                ),
            fallbackReps = emptyList(),
        )

    fun onPrevRepClicked() {
        _screenState.update { state -> state.withCurrentIndex((currentIndexOf(state) - 1).coerceAtLeast(0)) }
    }

    fun onNextRepClicked() {
        _screenState.update { state ->
            val maxIndex = (totalRepsOf(state) - 1).coerceAtLeast(0)
            state.withCurrentIndex((currentIndexOf(state) + 1).coerceAtMost(maxIndex))
        }
    }

    private fun currentIndexOf(state: MotionReplayScreenState) = state.uiState?.currentIndex ?: 0

    private fun totalRepsOf(state: MotionReplayScreenState): Int {
        val uiState = state.uiState ?: return 0
        return if (uiState.isReplayAvailable) uiState.reps.size else state.fallbackReps.size
    }

    private fun MotionReplayScreenState.withCurrentIndex(index: Int): MotionReplayScreenState =
        uiState?.let { copy(uiState = it.copy(currentIndex = index)) } ?: this
}