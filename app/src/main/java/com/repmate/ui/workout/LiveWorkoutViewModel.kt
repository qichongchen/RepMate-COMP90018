package com.repmate.ui.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.CalibrationRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.FormScorer
import com.repmate.engine.JumpingJackRepDetector
import com.repmate.engine.MotionFrame
import com.repmate.engine.PushupRepDetector
import com.repmate.engine.RepEvent
import com.repmate.engine.RepPhase
import com.repmate.engine.RepScore
import com.repmate.engine.SquatRepDetector
import com.repmate.engine.WorkoutSession
import com.repmate.sensors.SensorSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** What [LiveWorkoutScreen] renders. */
data class LiveWorkoutUiState(
    val exercise: ExerciseType = ExerciseType.SQUAT,
    val repCount: Int = 0,
    val phase: RepPhase = RepPhase.IDLE,
    val elapsedMillis: Long = 0L,
    val lastRepScore: RepScore? = null,
    val isPaused: Boolean = false,
)

/**
 * Backs [LiveWorkoutScreen]. Unlike `CalibrationViewModel`, this one is wired to real sensor
 * data end to end: [SensorSource.frames] feeds the real detector for [ExerciseType.SQUAT]/
 * [ExerciseType.JUMPING_JACK] (squat and jumping-jack both have recorded-trace-backed detectors
 * already), each detected [RepEvent] is scored by the real [FormScorer] against the user's saved
 * [CalibrationProfile] (or `null`, which [FormScorer] already handles), and the score is
 * persisted for real via [SessionRepository] once the workout ends.
 *
 * [ExerciseType.PUSHUP] is the one exception -- see [PushupRepDetector]'s own KDoc for why it's
 * still a fake, and what a real one needs to replace it.
 */
@HiltViewModel
class LiveWorkoutViewModel
    @Inject
    constructor(
        private val sensorSource: SensorSource,
        private val calibrationRepository: CalibrationRepository,
        private val sessionRepository: SessionRepository,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        private val sessionId = UUID.randomUUID().toString()

        // Wall-clock, for WorkoutSession.startedAt only -- never mixed into detector timing,
        // which uses frame.tMillis (the sensor's boot-clock timestamp). See SensorSource's own
        // KDoc on why the two clocks must not be mixed.
        private val startedAtWallClockMs = System.currentTimeMillis()

        private val formScorer = FormScorer()
        private val repFeedback = RepFeedback(context)

        private val _uiState = MutableStateFlow(LiveWorkoutUiState())
        val uiState: StateFlow<LiveWorkoutUiState> = _uiState.asStateFlow()

        private val _workoutFinished = Channel<String>(Channel.BUFFERED)

        /** Fires once, carrying the session's id, once the finished session has been saved. */
        val workoutFinished = _workoutFinished.receiveAsFlow()

        // Same "applied once, from whatever the nav route parsed" pattern as
        // ForgotPasswordViewModel.onInitialEmail / CalibrationViewModel.onExerciseType.
        private lateinit var activeExerciseType: ExerciseType
        private var calibrationProfile: CalibrationProfile? = null
        private val rawReps = mutableListOf<RepEvent>()
        private val scoredReps = mutableListOf<RepScore>()

        private lateinit var processFrame: (MotionFrame) -> RepEvent?
        private lateinit var currentPhase: () -> RepPhase

        private var frameCollectionJob: Job? = null
        private var timerJob: Job? = null

        /** Called once by [LiveWorkoutScreen] with the real exercise type parsed from the nav route. */
        fun onExerciseType(exerciseType: ExerciseType) {
            if (::activeExerciseType.isInitialized) return
            activeExerciseType = exerciseType
            _uiState.update { it.copy(exercise = exerciseType) }

            viewModelScope.launch {
                calibrationProfile = calibrationRepository.getProfile(exerciseType)
                bindDetector(exerciseType)
                startTimer()
                collectFrames()
            }
        }

        /**
         * Picks the one real detector for [exerciseType] and closes over it, rather than storing
         * three detector fields and branching on every frame -- [processFrame]/[currentPhase] are
         * assigned together here so they can never end up referring to two different exercises.
         *
         * [JumpingJackRepDetector] has no [RepPhase] of its own (see its KDoc) -- its
         * [JumpingJackRepDetector.awaitingLanding] is mapped to [RepPhase.DESCENDING] (a movement
         * burst in progress) and its absence to [RepPhase.IDLE], the same two-phase reduction
         * [SquatRepDetector] itself uses for the same underlying reason: a burst-based detector
         * cannot tell descent from ascent, or an in-progress jump from a completed one, any more
         * finely than that.
         */
        private fun bindDetector(exerciseType: ExerciseType) {
            when (exerciseType) {
                ExerciseType.SQUAT -> {
                    val detector = SquatRepDetector(calibrationProfile)
                    processFrame = detector::process
                    currentPhase = { detector.phase }
                }
                ExerciseType.JUMPING_JACK -> {
                    val detector = JumpingJackRepDetector()
                    processFrame = detector::process
                    currentPhase = { if (detector.awaitingLanding) RepPhase.DESCENDING else RepPhase.IDLE }
                }
                ExerciseType.PUSHUP -> {
                    val detector = PushupRepDetector()
                    processFrame = detector::process
                    currentPhase = { detector.phase }
                }
            }
        }

        /**
         * Collects the live sensor stream for the rest of this ViewModel's lifetime. Pausing
         * (see [onPauseResumeClicked]) does not cancel this collection -- it just drops frames
         * before they reach the detector, so resuming needs no re-registration of the hardware
         * listener and accumulated detector/session state is untouched either way.
         */
        private fun collectFrames() {
            frameCollectionJob =
                viewModelScope.launch {
                    sensorSource.frames.collect { frame ->
                        if (_uiState.value.isPaused) return@collect
                        val repEvent = processFrame(frame)
                        _uiState.update { it.copy(phase = currentPhase()) }
                        if (repEvent != null) onRepDetected(repEvent)
                    }
                }
        }

        private fun onRepDetected(repEvent: RepEvent) {
            val score = formScorer.score(repEvent, calibrationProfile, previousReps = rawReps.toList())
            rawReps += repEvent
            scoredReps += score
            repFeedback.onRepDetected(scoredReps.size)
            _uiState.update { it.copy(repCount = scoredReps.size, lastRepScore = score) }
        }

        fun onPauseResumeClicked() {
            _uiState.update { it.copy(isPaused = !it.isPaused) }
        }

        /** Saves the session for real via [SessionRepository], then fires [workoutFinished]. */
        fun onEndWorkoutClicked() {
            if (!::activeExerciseType.isInitialized) return
            frameCollectionJob?.cancel()
            timerJob?.cancel()

            val session =
                WorkoutSession(
                    id = sessionId,
                    exercise = activeExerciseType,
                    startedAt = startedAtWallClockMs,
                    reps = scoredReps.toList(),
                    frames = null,
                )
            viewModelScope.launch {
                sessionRepository.save(session)
                _workoutFinished.send(sessionId)
            }
        }

        private fun startTimer() {
            timerJob =
                viewModelScope.launch {
                    while (true) {
                        delay(1000)
                        if (!_uiState.value.isPaused) {
                            _uiState.update { it.copy(elapsedMillis = it.elapsedMillis + 1000) }
                        }
                    }
                }
        }

        override fun onCleared() {
            // frameCollectionJob is cancelled here too (not just in onEndWorkoutClicked) so
            // backing out of the screen without tapping "End workout" also stops the sensor
            // listener -- cancelling this coroutine's collection is what reaches DeviceSensorSource's
            // `awaitClose { stop() }`.
            frameCollectionJob?.cancel()
            timerJob?.cancel()
            repFeedback.release()
        }
    }
