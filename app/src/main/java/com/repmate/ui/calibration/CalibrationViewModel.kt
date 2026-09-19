package com.repmate.ui.calibration

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.CalibrationRepository
import com.repmate.engine.CalibrationOutcome
import com.repmate.engine.ExerciseType
import com.repmate.sensors.SensorSource
import com.repmate.ui.workout.RepFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What [CalibrationScreen] renders. [Accepted] is transient: nothing is drawn for it, it exists
 * only so the screen can observe the transition and navigate away -- see [CalibrationScreen]'s
 * `LaunchedEffect(uiState)`.
 */
sealed interface CalibrationUiState {
    /** Instructions and a Start button. Nothing is being recorded. */
    data class Ready(val exerciseType: ExerciseType) : CalibrationUiState

    /** Phone going into the pocket. Frames are not collected until this reaches zero. */
    data class CountingDown(val exerciseType: ExerciseType, val secondsLeft: Int) : CalibrationUiState

    /**
     * Real frames flowing into the real detector. [repsCompleted] only moves when the detector
     * counts a rep; [isMoving] shows it reacting before then; [showNoRepHint] appears after
     * [CalibrationViewModel.NO_REP_HINT_AFTER_MS] without a rep.
     */
    data class Capturing(
        val exerciseType: ExerciseType,
        val repsCompleted: Int,
        val isMoving: Boolean = false,
        val showNoRepHint: Boolean = false,
    ) : CalibrationUiState

    /** [CalibrationOutcome.Rejected]: nothing was saved. [detail] is the engine's log line. */
    data class Rejected(
        val exerciseType: ExerciseType,
        val reason: CalibrationOutcome.Reason,
        val detail: String,
    ) : CalibrationUiState

    /** The exercise has no real detector to calibrate with -- see [CalibrationCapture.isSupported]. */
    data class Unsupported(val exerciseType: ExerciseType) : CalibrationUiState

    object Accepted : CalibrationUiState
}

/**
 * Backs [CalibrationScreen]: runs a [CalibrationCapture] over the live [SensorSource] stream --
 * the same injected source [com.repmate.ui.workout.LiveWorkoutViewModel] collects -- and hands
 * the five real reps it captures to [com.repmate.engine.CalibrationProfile.evaluate]. Only an
 * accepted profile is saved; a rejection is shown and saves nothing.
 *
 * The capture window is the whole set rather than one window per rep: a countdown while the
 * phone goes into the pocket (so pocketing is never measured), then frames until the fifth rep,
 * then collection stops. See the "permissive guards, not prompted windows" note on
 * [com.repmate.engine.CalibrationProfile] for why reps are not individually prompted.
 */
@HiltViewModel
class CalibrationViewModel
    @Inject
    constructor(
        private val sensorSource: SensorSource,
        private val calibrationRepository: CalibrationRepository,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow<CalibrationUiState>(CalibrationUiState.Ready(exerciseType = ExerciseType.SQUAT))
        val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

        private val repFeedback = RepFeedback(context)

        // Same pattern as ForgotPasswordViewModel.onInitialEmail: applied once, from whatever the
        // nav route actually parsed, then ignored on every later recomposition.
        private var initializedExerciseType: ExerciseType? = null
        private var captureJob: Job? = null
        private var noRepHintJob: Job? = null

        /** Called once by [CalibrationScreen] with the real exercise type parsed from the nav route. */
        fun onExerciseType(exerciseType: ExerciseType) {
            if (initializedExerciseType != null) return
            initializedExerciseType = exerciseType
            _uiState.value =
                if (CalibrationCapture.isSupported(exerciseType)) {
                    CalibrationUiState.Ready(exerciseType)
                } else {
                    CalibrationUiState.Unsupported(exerciseType)
                }
        }

        fun onStartClicked() {
            if (_uiState.value is CalibrationUiState.Ready) startCapture()
        }

        fun onTryAgainClicked() {
            if (_uiState.value is CalibrationUiState.Rejected) startCapture()
        }

        /** Countdown, then real frames into a fresh [CalibrationCapture] until it completes. */
        private fun startCapture() {
            val exerciseType = initializedExerciseType ?: return
            stopCapture()
            captureJob =
                viewModelScope.launch {
                    for (secondsLeft in COUNTDOWN_SECONDS downTo 1) {
                        _uiState.value = CalibrationUiState.CountingDown(exerciseType, secondsLeft)
                        delay(1000)
                    }

                    val capture = CalibrationCapture(exerciseType)
                    Log.i(TAG, "$exerciseType capture started")
                    _uiState.value = CalibrationUiState.Capturing(exerciseType, repsCompleted = 0)
                    restartNoRepHint()

                    // Collection ends by cancelling this job from inside the collector once the
                    // fifth rep lands, which is also what unregisters the sensor listener.
                    sensorSource.frames.collect { frame ->
                        val rep = capture.onFrame(frame)
                        if (rep != null) {
                            // One line per captured rep, so a derived value in the profile below
                            // can be traced to the rep that set it.
                            val gapMs = capture.reps.getOrNull(capture.reps.size - 2)?.let { rep.startMs - it.endMs }
                            Log.i(
                                TAG,
                                "rep ${capture.reps.size}: duration ${rep.endMs - rep.startMs} ms, " +
                                    "amplitude %.2f, gap after previous ${gapMs?.let { "$it ms" } ?: "-"}".format(rep.amplitude),
                            )
                            repFeedback.onRepDetected(capture.reps.size)
                            restartNoRepHint()
                        }
                        _uiState.update { state ->
                            (state as? CalibrationUiState.Capturing)?.copy(
                                repsCompleted = capture.reps.size,
                                isMoving = capture.isMoving,
                                showNoRepHint = state.showNoRepHint && rep == null,
                            ) ?: state
                        }
                        if (capture.isComplete) {
                            finish(capture)
                            stopCapture()
                        }
                    }
                }
        }

        private fun finish(capture: CalibrationCapture) {
            when (val outcome = capture.evaluate()) {
                is CalibrationOutcome.Accepted -> {
                    Log.i(TAG, "${capture.exerciseType} " + outcome.profile.describe())
                    // A separate coroutine, not captureJob: that job is cancelled right after this
                    // returns, and the save must not be cancelled with it.
                    viewModelScope.launch {
                        calibrationRepository.saveProfile(capture.exerciseType, outcome.profile)
                        _uiState.value = CalibrationUiState.Accepted
                    }
                }
                is CalibrationOutcome.Rejected -> {
                    Log.i(TAG, "${capture.exerciseType} calibration rejected: ${outcome.reason} -- ${outcome.detail}")
                    _uiState.value = CalibrationUiState.Rejected(capture.exerciseType, outcome.reason, outcome.detail)
                }
            }
        }

        private fun restartNoRepHint() {
            noRepHintJob?.cancel()
            noRepHintJob =
                viewModelScope.launch {
                    delay(NO_REP_HINT_AFTER_MS)
                    _uiState.update { (it as? CalibrationUiState.Capturing)?.copy(showNoRepHint = true) ?: it }
                }
        }

        private fun stopCapture() {
            noRepHintJob?.cancel()
            captureJob?.cancel()
        }

        override fun onCleared() {
            stopCapture()
            repFeedback.release()
        }

        companion object {
            private const val TAG = "RepMateCalibration"

            /** Long enough to tap Start and put the phone in a front pocket. */
            const val COUNTDOWN_SECONDS = 5

            /** A rep takes 1-3 s; this long without one means something is off, not slow. */
            const val NO_REP_HINT_AFTER_MS = 10_000L
        }
    }
