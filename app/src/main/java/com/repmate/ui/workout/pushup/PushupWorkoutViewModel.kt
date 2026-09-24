package com.repmate.ui.workout.pushup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.PushupRepDetector
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import com.repmate.pose.Arm
import com.repmate.pose.ArmLock
import com.repmate.pose.Tracking
import com.repmate.pose.elbowAngleDegrees
import com.repmate.pose.trackingState
import com.repmate.safety.CheckInScheduler
import com.repmate.safety.SafetyCheckInPreferences
import com.repmate.ui.theme.WorkoutPreferences
import com.repmate.ui.workout.RepFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** What [PushupWorkoutScreen] renders. */
data class PushupWorkoutUiState(
    val repCount: Int = 0,
    val phase: PushupRepDetector.Phase = PushupRepDetector.Phase.WAITING,
    val tracking: Tracking = Tracking.NO_PERSON,
    val armLocked: Boolean = false,
    val elapsedMillis: Long = 0L,
)

/**
 * Backs [PushupWorkoutScreen]. The camera-driven counterpart to `LiveWorkoutViewModel`, kept
 * separate rather than folded into it: that ViewModel is built end to end around a
 * `Flow<MotionFrame>` from the IMU, and push-ups instead need a pose angle stream from the camera
 * -- different enough input shapes that sharing one ViewModel would mean branching most of its
 * methods on exercise type.
 *
 * ## The pipeline
 * [PushupWorkoutScreen] owns the camera and ML Kit pose detector (both need a `LifecycleOwner`,
 * which a `ViewModel` isn't) and feeds each frame's landmarks to this class as a pair of [Arm]s
 * via [onPose]. From there:
 * 1. [armLock] settles on one arm for the set and sticks with it (see its own KDoc for why).
 * 2. Once locked, [elbowAngleDegrees] turns that arm into a single angle.
 * 3. [repDetector] -- pure Kotlin, unit-tested on its own -- turns the angle stream into rep
 *    transitions.
 *
 * ## No calibration
 * Unlike squat and jumping jack, push-ups skip calibration entirely for now (see
 * `CalibrationUiState.Unsupported` and Home's routing in `NavGraph.kt`): there is no per-user
 * profile yet to score depth or tempo against, so every counted rep is recorded with a neutral,
 * un-scored [RepScore] rather than passed through `FormScorer`. That is a placeholder, not a
 * design decision -- per-user calibration for push-ups is the intended replacement.
 */
@HiltViewModel
class PushupWorkoutViewModel
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val safetyCheckInPreferences: SafetyCheckInPreferences,
        private val checkInScheduler: CheckInScheduler,
        private val workoutPreferences: WorkoutPreferences,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        private val sessionId = UUID.randomUUID().toString()
        private val startedAtWallClockMs = System.currentTimeMillis()

        private val repFeedback = RepFeedback(context)

        // StateFlows so the current setting is readable synchronously at rep-detection time, with
        // no suspend call on the hot path. Eagerly so the stored value is already loaded by the
        // first rep. Initial values must match WorkoutPreferences' own defaults.
        private val hapticFeedbackEnabled =
            workoutPreferences.isHapticFeedbackEnabled
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)
        private val spokenRepCountEnabled =
            workoutPreferences.isSpokenRepCountEnabled
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        private val armLock = ArmLock()
        private val repDetector = PushupRepDetector()

        private val _uiState = MutableStateFlow(PushupWorkoutUiState())
        val uiState: StateFlow<PushupWorkoutUiState> = _uiState.asStateFlow()

        private val _workoutFinished = Channel<String>(Channel.BUFFERED)
        val workoutFinished = _workoutFinished.receiveAsFlow()

        private val scoredReps = mutableListOf<RepScore>()
        private var lastRepAtElapsedMs: Long = SystemClock.elapsedRealtime()

        private var timerJob: Job? = null

        init {
            startTimer()
        }

        /** Called on the main thread with one frame's arms, once ML Kit has processed it. */
        fun onPose(left: Arm?, right: Arm?) {
            armLock.observe(left, right)
            val locked = armLock.isLocked
            val arm = if (locked) armLock.armFor(left, right) else null

            val tracking = trackingState(arm, MIN_CONFIDENCE, previous = _uiState.value.tracking)

            if (locked) {
                val angle = arm?.let { elbowAngleDegrees(it) }
                // Same reasoning as the probe this was ported from: once locked, the counter is fed
                // whatever the tracking label says, since a gate that flips several times a second
                // would otherwise count nothing.
                val transition = repDetector.update(angle)
                if (transition?.repCompleted == true) onRepCompleted()
            }

            _uiState.update {
                it.copy(
                    repCount = repDetector.count,
                    phase = repDetector.phase,
                    tracking = tracking,
                    armLocked = locked,
                )
            }
        }

        private fun onRepCompleted() {
            val now = SystemClock.elapsedRealtime()
            val tempoSeconds = (now - lastRepAtElapsedMs) / 1000f
            lastRepAtElapsedMs = now

            // No calibration profile exists for push-ups yet (calibration is skipped entirely --
            // see this class's KDoc), so there is nothing to score depth or tempo against. A
            // counted rep is a real rep -- the state machine only reaches here on a genuine
            // straight-bent-straight cycle -- it just isn't graded yet.
            val score =
                RepScore(
                    repIndex = repDetector.count - 1,
                    score = 10f,
                    tempoSeconds = tempoSeconds,
                    rangePercent = 100,
                    pauseSeconds = 0f,
                    reasons = listOf("not scored: push-ups skip calibration for now"),
                )
            scoredReps += score
            Log.i(TAG, "push-up rep ${scoredReps.size}: tempo %.1fs".format(tempoSeconds))
            repFeedback.onRepDetected(scoredReps.size, hapticFeedbackEnabled.value, spokenRepCountEnabled.value)
        }

        /** Resets the count and arm lock for a fresh set -- used if the camera is re-bound. */
        fun onResetClicked() {
            armLock.reset()
            repDetector.reset()
            scoredReps.clear()
            lastRepAtElapsedMs = SystemClock.elapsedRealtime()
            _uiState.update { PushupWorkoutUiState(elapsedMillis = it.elapsedMillis) }
        }

        /** Saves the session for real via [SessionRepository], then fires [workoutFinished]. */
        fun onEndWorkoutClicked() {
            Log.i(TAG, "push-up workout ended: ${scoredReps.size} reps counted")
            timerJob?.cancel()

            val session =
                WorkoutSession(
                    id = sessionId,
                    exercise = ExerciseType.PUSHUP,
                    startedAt = startedAtWallClockMs,
                    reps = scoredReps.toList(),
                    // No MotionFrame data exists for a camera workout -- see WorkoutSession's own
                    // KDoc on why this field is nullable. MotionReplay already handles a session
                    // with no frames (every session does today; Room has no column for them yet).
                    frames = null,
                )
            viewModelScope.launch {
                sessionRepository.save(session)
                // Safety check-in, if the user opted in from Profile -- see CheckInScheduler's
                // KDoc for the notify/escalate chain this kicks off.
                if (safetyCheckInPreferences.isEnabledSnapshot()) {
                    checkInScheduler.scheduleAfterWorkout()
                }
                _workoutFinished.send(sessionId)
            }
        }

        private fun startTimer() {
            timerJob =
                viewModelScope.launch {
                    while (true) {
                        delay(1000)
                        _uiState.update { it.copy(elapsedMillis = it.elapsedMillis + 1000) }
                    }
                }
        }

        override fun onCleared() {
            timerJob?.cancel()
            repFeedback.release()
        }

        private companion object {
            const val TAG = "RepMatePushupWorkout"

            /** Starting threshold for "every point of the locked arm is in frame"; see [Tracking]. */
            const val MIN_CONFIDENCE = 0.5f
        }
    }
