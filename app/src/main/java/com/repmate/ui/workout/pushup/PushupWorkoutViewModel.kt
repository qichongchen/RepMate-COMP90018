package com.repmate.ui.workout.pushup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.repmate.BuildConfig
import com.repmate.data.repo.SessionRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.PushupRepDetector
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import com.repmate.pose.Arm
import com.repmate.pose.CountingStatus
import com.repmate.pose.Tracking
import com.repmate.pose.countingStatus
import com.repmate.data.memory.JustFinishedSessionStore
import com.repmate.safety.CheckInScheduler
import com.repmate.safety.SafetyCheckInPreferences
import com.repmate.sensors.PhoneStabilityGate
import com.repmate.sensors.SensorSource
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/** Which camera the workout reads. Rear is the default: the phone is propped up and faces the user's side. */
enum class CameraFacing {
    REAR,
    FRONT,
    ;

    fun toggled(): CameraFacing = if (this == REAR) FRONT else REAR
}

/**
 * The stability gate's live readings, for the debug-only readout on the workout screen.
 *
 * @property level the larger of the two readings as a fraction of its limit; above 1.0 counting pauses.
 */
data class MotionReadout(
    val level: Float,
    val gyroRmsRadPerSec: Float,
    val accelStdMps2: Float,
    val gyroLimitRadPerSec: Float,
    val accelLimitMps2: Float,
)

/** What [PushupWorkoutScreen] renders. */
data class PushupWorkoutUiState(
    val repCount: Int = 0,
    val phase: PushupRepDetector.Phase = PushupRepDetector.Phase.WAITING,
    val tracking: Tracking = Tracking.NO_PERSON,
    val armLocked: Boolean = false,
    val elapsedMillis: Long = 0L,
    val status: CountingStatus = CountingStatus.NO_PERSON,
    val cameraFacing: CameraFacing = CameraFacing.REAR,
    /** Only filled in debug builds. */
    val motion: MotionReadout? = null,
    /** Mirrors [WorkoutPreferences.isHapticFeedbackEnabled] -- the same global setting as Profile's toggle. */
    val hapticFeedbackEnabled: Boolean = true,
    /** Mirrors [WorkoutPreferences.isSpokenRepCountEnabled] -- the same global setting as Profile's toggle. */
    val spokenRepCountEnabled: Boolean = false,
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
 * via [onPose]. From there, [PushupFrameProcessor] -- pure Kotlin, unit-tested -- decides whether
 * the frame may count (phone steady, a confident person in frame, an arm locked) and if so turns
 * that arm into an angle for [PushupRepDetector], which turns the angle stream into rep
 * transitions. This class only wires those to Android: the camera frames in, the sensor stream
 * into [PhoneStabilityGate], the results out to [uiState] and [RepFeedback].
 *
 * ## Two inputs, one gate
 * The IMU stream (the same [SensorSource] the squat and jumping-jack workouts use) is collected
 * for the life of this ViewModel purely to tell whether the phone is being moved -- background
 * motion from a shifting phone otherwise looks to the pose detector like a person moving. Neither
 * stream drives the other; [onPose] simply reads the gate's latest answer.
 *
 * ## Rep count and resets
 * The count shown, spoken and saved is [scoredReps]'s size, not the detector's own count: the
 * detector is reset when the camera is switched or a long block ends, and a rep already counted
 * must survive that.
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
        private val justFinishedSessions: JustFinishedSessionStore,
        private val safetyCheckInPreferences: SafetyCheckInPreferences,
        private val checkInScheduler: CheckInScheduler,
        private val workoutPreferences: WorkoutPreferences,
        private val sensorSource: SensorSource,
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
        private val frameProcessor = PushupFrameProcessor()

        /**
         * Each counted rep's elbow-angle curve, for the report this set ends on. Push-ups have no
         * accelerometer movement to replay -- the phone is propped up and held still -- so the
         * angle is collected as the set happens. See [PushupAngleTrace].
         */
        private val angleTrace = PushupAngleTrace()
        private val stabilityGate = PhoneStabilityGate()

        // Everything the workout itself changes. The two feedback settings are folded in below
        // from the flows above -- see LiveWorkoutViewModel for why they are not written here.
        private val _uiState = MutableStateFlow(PushupWorkoutUiState())

        val uiState: StateFlow<PushupWorkoutUiState> =
            combine(_uiState, hapticFeedbackEnabled, spokenRepCountEnabled) { state, haptic, spoken ->
                state.copy(hapticFeedbackEnabled = haptic, spokenRepCountEnabled = spoken)
            }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                _uiState.value.copy(
                    hapticFeedbackEnabled = hapticFeedbackEnabled.value,
                    spokenRepCountEnabled = spokenRepCountEnabled.value,
                ),
            )

        private val _workoutFinished = Channel<String>(Channel.BUFFERED)
        val workoutFinished = _workoutFinished.receiveAsFlow()

        private val scoredReps = mutableListOf<RepScore>()
        private var bestBottomDegrees: Double? = null
        private var lastRepAtElapsedMs: Long = SystemClock.elapsedRealtime()

        private var timerJob: Job? = null
        private var sensorJob: Job? = null
        private val readoutThrottle = Throttle(READOUT_INTERVAL_MS)
        private val motionLogThrottle = Throttle(MOTION_LOG_INTERVAL_MS)

        init {
            startTimer()
            collectSensorFrames()
        }

        /** Called on the main thread with one frame's arms, once ML Kit has processed it. */
        fun onPose(left: Arm?, right: Arm?) {
            val frameAtMs = SystemClock.elapsedRealtime()
            val result =
                frameProcessor.process(
                    left = left,
                    right = right,
                    phoneStable = stabilityGate.isStable,
                    nowMs = frameAtMs,
                )
            angleTrace.onFrame(
                nowMs = frameAtMs,
                phase = result.phase,
                smoothedDegrees = result.smoothedDegrees,
                repCompleted = result.repCompleted,
                bottomDegrees = result.repBottomDegrees,
            )
            if (result.status != _uiState.value.status) {
                Log.i(TAG, "counting status: ${_uiState.value.status} -> ${result.status}")
            }
            if (result.repCompleted) onRepCompleted(result.repBottomDegrees)

            _uiState.update {
                it.copy(
                    repCount = scoredReps.size,
                    phase = result.phase,
                    tracking = result.tracking,
                    armLocked = result.armLocked,
                    status = result.status,
                )
            }
        }

        /**
         * Collects the IMU stream for the rest of this ViewModel's lifetime, solely to feed
         * [stabilityGate]. Cancelling it (in [onEndWorkoutClicked] and [onCleared]) is what reaches
         * `DeviceSensorSource`'s `awaitClose { stop() }` and releases the hardware.
         */
        private fun collectSensorFrames() {
            sensorJob =
                viewModelScope.launch {
                    sensorSource.frames.collect { frame ->
                        val wasStable = stabilityGate.isStable
                        stabilityGate.onFrame(frame)
                        val stable = stabilityGate.isStable

                        if (stable != wasStable) {
                            Log.i(
                                TAG,
                                "phone ${if (stable) "steady" else "moving"}: " + motionSummary(),
                            )
                        }
                        if (motionLogThrottle.ready(frame.tMillis)) {
                            Log.i(TAG, "motion: " + motionSummary())
                        }

                        val readoutDue = BuildConfig.DEBUG && readoutThrottle.ready(frame.tMillis)
                        if (stable != wasStable || readoutDue) {
                            _uiState.update {
                                it.copy(
                                    // The camera may be quiet for a moment; do not wait for the next pose
                                    // frame to show "hold the phone still".
                                    status = countingStatus(stable, it.tracking, it.armLocked),
                                    motion = if (readoutDue) currentReadout() else it.motion,
                                )
                            }
                        }
                    }
                }
        }

        private fun currentReadout(): MotionReadout {
            val config = stabilityGate.config
            return MotionReadout(
                level = stabilityGate.level.toFloat(),
                gyroRmsRadPerSec = stabilityGate.gyroRmsRadPerSec.toFloat(),
                accelStdMps2 = stabilityGate.accelStdMps2.toFloat(),
                gyroLimitRadPerSec = config.gyroLimitRadPerSec.toFloat(),
                accelLimitMps2 = config.accelLimitMps2.toFloat(),
            )
        }

        private fun motionSummary(): String {
            val config = stabilityGate.config
            return "level %.2f (gyro %.3f/%.3f rad/s, accel std %.3f/%.3f m/s^2)".format(
                stabilityGate.level,
                stabilityGate.gyroRmsRadPerSec,
                config.gyroLimitRadPerSec,
                stabilityGate.accelStdMps2,
                config.accelLimitMps2,
            )
        }

    private fun onRepCompleted(bottomDegrees: Double?) {
        val now = SystemClock.elapsedRealtime()
        val tempoSeconds = (now - lastRepAtElapsedMs) / 1000f
        lastRepAtElapsedMs = now

        val reasons = mutableListOf<String>()
        var score = MAX_SCORE
        val rangePercent: Int

        val best = bestBottomDegrees
        if (bottomDegrees == null) {
            rangePercent = NOT_MEASURABLE_RANGE_PERCENT
            reasons += "depth not measurable for this rep"
        } else if (best == null) {
            rangePercent = 100
            reasons += "good depth"
            bestBottomDegrees = bottomDegrees
        } else {
            val depthRatio = (UP_DEGREES - bottomDegrees) / (UP_DEGREES - best)
            rangePercent = (depthRatio * 100).roundToInt().coerceIn(0, 100)
            if (depthRatio < 1.0) {
                val shortfall = (1.0 - depthRatio).coerceIn(0.0, 1.0)
                score -= (shortfall * DEPTH_WEIGHT).toFloat()
                reasons += "not as deep as your best rep this session"
            } else {
                reasons += "good depth"
            }
            bestBottomDegrees = minOf(best, bottomDegrees)
        }

        val tempoMs = (tempoSeconds * 1000).toLong()
        when {
            tempoMs < FALLBACK_SHORTEST_MS -> {
                val shortfall = ((FALLBACK_SHORTEST_MS - tempoMs).toFloat() / FALLBACK_SHORTEST_MS).coerceIn(0f, 1f)
                score -= shortfall * TEMPO_WEIGHT
                reasons += "rushed"
            }
            tempoMs > FALLBACK_LONGEST_MS -> {
                val excess = ((tempoMs - FALLBACK_LONGEST_MS).toFloat() / FALLBACK_LONGEST_MS).coerceIn(0f, 1f)
                score -= excess * TEMPO_WEIGHT
                reasons += "slower than usual"
            }
            else -> reasons += "good tempo"
        }

        if (scoredReps.size >= MIN_HISTORY_FOR_CONSISTENCY && rangePercent >= 0) {
            val pastAndCurrent = scoredReps.mapNotNull { it.rangePercent.takeIf { p -> p >= 0 } } + rangePercent
            val meanShortfall = pastAndCurrent.map { maxOf(0, 100 - it) / 100f }.average().toFloat()
            if (meanShortfall > CONSISTENCY_DEVIATION_THRESHOLD) {
                score -= CONSISTENCY_WEIGHT
                reasons += "inconsistent with your best depth this session"
            }
        }

        val repScore =
            RepScore(
                repIndex = scoredReps.size,
                score = score.coerceIn(0f, MAX_SCORE),
                tempoSeconds = tempoSeconds,
                rangePercent = rangePercent,
                pauseSeconds = 0f,
                reasons = reasons,
            )
        scoredReps += repScore
        Log.i(TAG, "push-up rep ${scoredReps.size}: tempo %.1fs, range $rangePercent".format(tempoSeconds))
        repFeedback.onRepDetected(scoredReps.size, hapticFeedbackEnabled.value, spokenRepCountEnabled.value)
    }

        /** Persists the buzz-on-rep setting -- the same one Profile's toggle writes, not a per-session override. */
        fun onHapticFeedbackToggled(enabled: Boolean) {
            viewModelScope.launch { workoutPreferences.setHapticFeedbackEnabled(enabled) }
        }

        /** Persists the spoken-rep-count setting -- the same one Profile's toggle writes, not a per-session override. */
        fun onSpokenRepCountToggled(enabled: Boolean) {
            viewModelScope.launch { workoutPreferences.setSpokenRepCountEnabled(enabled) }
        }

        /**
         * Switches between the rear and front camera. Keeps the reps already counted, but forgets
         * the arm lock and any half-finished rep: the geometry the lock was chosen on no longer holds.
         */
        fun onCameraToggled() {
            val facing = _uiState.value.cameraFacing.toggled()
            Log.i(TAG, "camera switched to $facing")
            frameProcessor.reset()
            _uiState.update {
                it.copy(
                    cameraFacing = facing,
                    phase = PushupRepDetector.Phase.WAITING,
                    tracking = Tracking.NO_PERSON,
                    armLocked = false,
                    status = countingStatus(stabilityGate.isStable, Tracking.NO_PERSON, armLocked = false),
                )
            }
        }

        /** Multiplies both stability limits by [factor] -- above 1 is more forgiving. Debug tuning. */
        fun onMotionLimitsScaled(factor: Double) {
            stabilityGate.config = stabilityGate.config.scaledBy(factor)
            Log.i(TAG, "stability limits now: " + motionSummary())
        }

        /** Resets the count and arm lock for a fresh set. */
        fun onResetClicked() {
            frameProcessor.reset()
            angleTrace.reset()
            justFinishedSessions.clear()
            scoredReps.clear()
            bestBottomDegrees = null
            lastRepAtElapsedMs = SystemClock.elapsedRealtime()
            _uiState.update {
                PushupWorkoutUiState(
                    elapsedMillis = it.elapsedMillis,
                    cameraFacing = it.cameraFacing,
                    motion = it.motion,
                )
            }
        }

        /** Saves the session for real via [SessionRepository], then fires [workoutFinished]. */
        fun onEndWorkoutClicked() {
            Log.i(TAG, "push-up workout ended: ${scoredReps.size} reps counted")
            timerJob?.cancel()
            sensorJob?.cancel()

            val session =
                WorkoutSession(
                    id = sessionId,
                    exercise = ExerciseType.PUSHUP,
                    startedAt = startedAtWallClockMs,
                    endedAt = System.currentTimeMillis(),
                    reps = scoredReps.toList(),
                    // No MotionFrame data exists for a camera workout -- see WorkoutSession's own
                    // KDoc on why this field is nullable. MotionReplay already handles a session
                    // with no frames (every session does today; Room has no column for them yet).
                    frames = null,
                )
            // The angle curves go to memory only, for the report this set ends on. Room stores
            // no curve for a push-up any more than it stores frames for a squat.
            justFinishedSessions.rememberPushupTraces(sessionId, angleTrace.reps)

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
            // sensorJob is cancelled here as well as in onEndWorkoutClicked, so backing out without
            // tapping "End workout" still releases the IMU.
            timerJob?.cancel()
            sensorJob?.cancel()
            repFeedback.release()
        }

        private companion object {
            const val TAG = "RepMatePushupWorkout"

            /** How often the debug readout refreshes; the gate itself runs on every frame. */
            const val READOUT_INTERVAL_MS = 200L

            /** How often the motion level is written to logcat, for tuning the limits. */
            const val MOTION_LOG_INTERVAL_MS = 1_000L
            const val MAX_SCORE = 10f
            const val UP_DEGREES = PushupRepDetector.DEFAULT_UP_DEGREES
            const val DEPTH_WEIGHT = 4f
            const val TEMPO_WEIGHT = 3f
            const val CONSISTENCY_WEIGHT = 1.5f
            const val FALLBACK_SHORTEST_MS = 1200L
            const val FALLBACK_LONGEST_MS = 3500L
            const val CONSISTENCY_DEVIATION_THRESHOLD = 0.35f
            const val MIN_HISTORY_FOR_CONSISTENCY = 2
            const val NOT_MEASURABLE_RANGE_PERCENT = -1
        }
    }
