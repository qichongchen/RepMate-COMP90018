package com.repmate.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.CalibrationRepository
import com.repmate.engine.CalibrationOutcome
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How often the fake capture loop below counts a rep. Demo pacing only, see [CalibrationViewModel]. */
private const val FAKE_REP_INTERVAL_MS = 1200L

/**
 * What [CalibrationScreen] renders. [Accepted] is transient: nothing is drawn for it, it exists
 * only so the screen can observe the transition and navigate away -- see [CalibrationScreen]'s
 * `LaunchedEffect(uiState)`.
 */
sealed interface CalibrationUiState {
    data class Recording(val exerciseType: ExerciseType, val repsCompleted: Int) : CalibrationUiState

    data class Rejected(val reason: CalibrationOutcome.Reason, val detail: String) : CalibrationUiState

    object Accepted : CalibrationUiState
}

/**
 * Backs [CalibrationScreen]. [CalibrationProfile.evaluate] (the real engine contract) decides
 * Accepted vs. Rejected; this class owns only the screen-local capture loop and, on acceptance,
 * persisting the result.
 *
 * The rep-capture loop is a fake, on a fixed clock rather than real motion data -- see
 * [startRecording] and [buildFakeSamples] for exactly where the real detector stream replaces it.
 * Because the fake samples are deliberately uniform, [onFinishClicked] always evaluates to
 * [CalibrationOutcome.Accepted] in practice; [CalibrationUiState.Rejected] is exercised by
 * [CalibrationScreen]'s own previews instead of by this loop, the same way every other screen in
 * this app previews a non-golden-path state directly rather than forcing its ViewModel down that
 * path.
 */
@HiltViewModel
class CalibrationViewModel
    @Inject
    constructor(
        private val calibrationRepository: CalibrationRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow<CalibrationUiState>(
                CalibrationUiState.Recording(exerciseType = ExerciseType.SQUAT, repsCompleted = 0),
            )
        val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

        // Same pattern as ForgotPasswordViewModel.onInitialEmail: applied once, from whatever the
        // nav route actually parsed, then ignored on every later recomposition so it can't stomp
        // on progress the fake capture loop has already made.
        private var initializedExerciseType: ExerciseType? = null
        private var captureJob: Job? = null

        /** Called once by [CalibrationScreen] with the real exercise type parsed from the nav route. */
        fun onExerciseType(exerciseType: ExerciseType) {
            if (initializedExerciseType != null) return
            initializedExerciseType = exerciseType
            startRecording(exerciseType)
        }

        private fun startRecording(exerciseType: ExerciseType) {
            captureJob?.cancel()
            _uiState.value = CalibrationUiState.Recording(exerciseType, repsCompleted = 0)
            // TODO(sensors, engine): this fixed-interval counter stands in for the real per-exercise
            // RepDetector's Flow<RepEvent> -- replace this loop with collecting that flow and
            // incrementing repsCompleted on each genuine detected rep instead of a clock tick.
            captureJob =
                viewModelScope.launch {
                    repeat(CalibrationProfile.REQUIRED_SAMPLES) {
                        delay(FAKE_REP_INTERVAL_MS)
                        _uiState.update { state ->
                            (state as? CalibrationUiState.Recording)?.copy(repsCompleted = state.repsCompleted + 1) ?: state
                        }
                    }
                }
        }

        fun onFinishClicked() {
            val recording = _uiState.value as? CalibrationUiState.Recording ?: return
            if (recording.repsCompleted < CalibrationProfile.REQUIRED_SAMPLES) return
            captureJob?.cancel()

            when (val outcome = CalibrationProfile.evaluate(buildFakeSamples())) {
                is CalibrationOutcome.Accepted -> {
                    viewModelScope.launch {
                        calibrationRepository.saveProfile(recording.exerciseType, outcome.profile)
                        _uiState.value = CalibrationUiState.Accepted
                    }
                }
                is CalibrationOutcome.Rejected -> {
                    _uiState.value = CalibrationUiState.Rejected(outcome.reason, outcome.detail)
                }
            }
        }

        fun onTryAgainClicked() {
            val exerciseType = initializedExerciseType ?: return
            startRecording(exerciseType)
        }

        /**
         * Fabricated [RepEvent]s standing in for whatever the real capture window would have
         * recorded -- see the TODO on [startRecording]. Deliberately uniform (equal amplitude,
         * duration, and gap) so [CalibrationProfile.evaluate] reliably accepts them, which is what
         * lets this screen be demoed end to end -- reaching Live Workout with a real, persisted
         * [CalibrationProfile] -- without a live detector.
         */
        private fun buildFakeSamples(): List<RepEvent> =
            List(CalibrationProfile.REQUIRED_SAMPLES) { index ->
                val start = index * 1500L
                RepEvent(index = index, startMs = start, endMs = start + 800L, amplitude = 2.0f)
            }
    }
