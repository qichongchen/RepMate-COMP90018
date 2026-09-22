package com.repmate.ui.workout

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.CalibrationRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.FormScorer
import com.repmate.engine.JumpingJackRepDetector
import com.repmate.engine.MotionFrame
import com.repmate.engine.RejectionGuard
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
 * Backs [LiveWorkoutScreen]. Wired to real sensor data end to end, like `CalibrationViewModel`:
 * [SensorSource.frames] feeds the real detector for [ExerciseType.SQUAT]/[ExerciseType.JUMPING_JACK] (squat and jumping-jack both have recorded-trace-backed detectors
 * already), each detected [RepEvent] is scored by the real [FormScorer] against the user's saved
 * [CalibrationProfile] (or `null`, which [FormScorer] already handles), and the score is
 * persisted for real via [SessionRepository] once the workout ends.
 *
 * [ExerciseType.PUSHUP] is not reachable through this screen any more: Home now routes it straight
 * to `com.repmate.ui.workout.pushup.PushupWorkoutScreen`, a camera-driven screen this ViewModel's
 * IMU-only design (a `Flow<MotionFrame>` in, a `RepEvent` out) can't fit -- push-ups need a pose
 * angle stream instead, which is what `com.repmate.engine.PushupRepDetector` consumes now. The
 * branch below is kept only so [bindDetector]'s `when` stays exhaustive against a route this
 * screen could still be reached by directly (an old deep link, a malformed nav argument); it
 * degrades to "no reps detected" rather than crashing, same reasoning as `NavGraph.kt`'s own
 * fallback for a malformed exercise-type argument.
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
        private val rejectedByGuard = mutableMapOf<RejectionGuard, Int>()

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
                Log.i(TAG, "$exerciseType workout started, " + (calibrationProfile?.describe() ?: "no profile: tuned defaults"))
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
                    detector.onRejectedWindow = { window ->
                        window.failures.forEach { rejectedByGuard.merge(it.guard, 1, Int::plus) }
                        Log.i(TAG, "rejected window: ${window.describe()}")
                    }
                    processFrame = detector::process
                    currentPhase = { detector.phase }
                }
                ExerciseType.JUMPING_JACK -> {
                    val detector = JumpingJackRepDetector()
                    processFrame = detector::process
                    currentPhase = { if (detector.awaitingLanding) RepPhase.DESCENDING else RepPhase.IDLE }
                }
                ExerciseType.PUSHUP -> {
                    // Not reachable via Home any more -- see this class's KDoc. Degrades to
                    // "nothing detected" rather than crashing if this screen is ever reached
                    // for PUSHUP some other way (e.g. a stale deep link).
                    processFrame = { null }
                    currentPhase = { RepPhase.IDLE }
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
            // Same shape as RepMateCalibration's per-rep line, so a workout's reps can be read
            // side by side with the calibration reps its profile was derived from.
            Log.i(
                TAG,
                "rep ${scoredReps.size}: duration ${repEvent.endMs - repEvent.startMs} ms, " +
                    "amplitude %.2f, score %.1f".format(repEvent.amplitude, score.score),
            )
            repFeedback.onRepDetected(scoredReps.size)
            _uiState.update { it.copy(repCount = scoredReps.size, lastRepScore = score) }
        }

        fun onPauseResumeClicked() {
            _uiState.update { it.copy(isPaused = !it.isPaused) }
        }

        /** Saves the session for real via [SessionRepository], then fires [workoutFinished]. */
        fun onEndWorkoutClicked() {
            if (!::activeExerciseType.isInitialized) return
            Log.i(TAG, "workout ended: ${scoredReps.size} reps counted, windows rejected by guard: " +
                (rejectedByGuard.takeIf { it.isNotEmpty() } ?: "none"))
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

        private companion object {
            const val TAG = "RepMateWorkout"
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
