package com.repmate.ui.calibration

import com.repmate.engine.CalibrationOutcome
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.JumpingJackRepDetector
import com.repmate.engine.MotionFrame
import com.repmate.engine.RepEvent
import com.repmate.engine.RepPhase
import com.repmate.engine.SquatRepDetector

/**
 * One calibration capture: real frames in, [CalibrationProfile.REQUIRED_SAMPLES] real reps out,
 * then [evaluate]. Plain Kotlin with no Android or coroutine dependencies, so the whole capture
 * path can be replayed against recorded traces in a JVM test -- [CalibrationViewModel] owns only
 * the timing around it (countdown, sensor collection, haptics, persistence).
 *
 * The detector per exercise:
 * - [ExerciseType.SQUAT]: [SquatRepDetector.forCalibrationCapture] -- the defaults with the
 *   duration floor lowered, so calibration can learn a pace the defaults would miss. See that
 *   factory for why no other guard is loosened.
 * - [ExerciseType.JUMPING_JACK]: the production [JumpingJackRepDetector], unchanged. None of its
 *   guards are calibrated, so there is no bootstrap problem to solve; what it does need is the
 *   paced cadence it was validated at, which is the caller's job (the metronome).
 * - [ExerciseType.PUSHUP]: not supported. The only push-up detector fabricates reps on a timer,
 *   and a profile built from those would be invented data saved as real -- [isSupported] exists
 *   so the caller can refuse before constructing one.
 *
 * Instances are single-use: once [isComplete], later frames are ignored, so frames still in
 * flight when the caller stops collecting cannot add a sixth rep.
 */
class CalibrationCapture(val exerciseType: ExerciseType) {
    init {
        require(isSupported(exerciseType)) { "No real detector to calibrate $exerciseType with" }
    }

    private val processFrame: (MotionFrame) -> RepEvent?
    private val movementInProgress: () -> Boolean

    init {
        when (exerciseType) {
            ExerciseType.SQUAT -> {
                val detector = SquatRepDetector.forCalibrationCapture()
                processFrame = detector::process
                movementInProgress = { detector.phase != RepPhase.IDLE }
            }
            ExerciseType.JUMPING_JACK -> {
                val detector = JumpingJackRepDetector()
                processFrame = detector::process
                movementInProgress = { detector.awaitingLanding }
            }
            ExerciseType.PUSHUP -> error("unreachable: rejected by isSupported")
        }
    }

    private val _reps = mutableListOf<RepEvent>()

    /** The real reps captured so far, at most [CalibrationProfile.REQUIRED_SAMPLES]. */
    val reps: List<RepEvent> get() = _reps

    /** True once enough reps have been captured to [evaluate]. */
    val isComplete: Boolean get() = _reps.size >= CalibrationProfile.REQUIRED_SAMPLES

    /**
     * True while the detector is inside a movement it has not yet judged -- a squat window
     * open, or a jumping jack's jump-out awaiting its landing. Shown on screen so the user can
     * see the sensor reacting before a rep is counted.
     */
    val isMoving: Boolean get() = !isComplete && movementInProgress()

    /**
     * Feeds one frame in.
     *
     * @return the rep this frame completed, or null -- always null once [isComplete].
     */
    fun onFrame(frame: MotionFrame): RepEvent? {
        if (isComplete) return null
        val rep = processFrame(frame) ?: return null
        _reps += rep
        return rep
    }

    /** Hands the captured reps to [CalibrationProfile.evaluate], unmodified. */
    fun evaluate(): CalibrationOutcome = CalibrationProfile.evaluate(_reps)

    companion object {
        /** Whether [exerciseType] has a real detector to calibrate with. */
        fun isSupported(exerciseType: ExerciseType): Boolean = exerciseType != ExerciseType.PUSHUP
    }
}
