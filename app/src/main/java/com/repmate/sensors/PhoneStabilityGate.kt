package com.repmate.sensors

import com.repmate.engine.MotionFrame
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The limits [PhoneStabilityGate] judges "the phone is moving" against.
 *
 * **These defaults are a first proposal, not measured values.** They come from typical phone-sensor
 * noise, not from a recording of a phone propped on a floor beside someone doing push-ups, which is
 * the number that matters: if floor vibration from the user's own hands lands above these limits,
 * the gate will pause counting during real reps. The workout screen shows the live readings in
 * debug builds and lets them be scaled ([scaledBy]) so that can be checked on the phone.
 *
 * @property gyroLimitRadPerSec limit on the RMS of the gyroscope's angular speed over the window.
 *   0.10 rad/s is about 6 degrees per second: a resting phone reads roughly 0.005, a phone held
 *   in a hand about 0.05 to 0.15, and a bump or a slide on the floor well over 0.5. Rotation is what
 *   moves the camera's view, so this is the limit that matters most for background motion.
 * @property accelLimitMps2 limit on the standard deviation of the accelerometer's magnitude over
 *   the window. Standard deviation, not the distance from 9.81, so a phone's fixed bias and its
 *   orientation (gravity along any axis) do not matter -- only change does. A resting phone is
 *   about 0.02; 0.40 m/s^2 is far above that and below a knock or a slide.
 * @property windowMs how much recent history each reading is computed over.
 * @property settleMs how long the readings must stay calm (below [releaseRatio] of the limits)
 *   before an unstable phone is called stable again.
 * @property releaseRatio fraction of the limits the readings must fall below to count as calm.
 *   Below 1 on purpose: a reading hovering right at a limit would otherwise flip the gate several
 *   times a second, the same two-level (Schmitt trigger) reasoning the rep detectors use.
 */
data class StabilityConfig(
    val gyroLimitRadPerSec: Double = DEFAULT_GYRO_LIMIT_RAD_PER_SEC,
    val accelLimitMps2: Double = DEFAULT_ACCEL_LIMIT_MPS2,
    val windowMs: Long = DEFAULT_WINDOW_MS,
    val settleMs: Long = DEFAULT_SETTLE_MS,
    val releaseRatio: Double = DEFAULT_RELEASE_RATIO,
) {
    init {
        require(gyroLimitRadPerSec > 0.0 && accelLimitMps2 > 0.0) { "limits must be positive" }
        require(windowMs > 0 && settleMs >= 0) { "windowMs must be positive and settleMs not negative" }
        require(releaseRatio > 0.0 && releaseRatio < 1.0) { "releaseRatio must be between 0 and 1, got $releaseRatio" }
    }

    /** The same config with both limits multiplied by [factor]; above 1 is more forgiving. */
    fun scaledBy(factor: Double): StabilityConfig =
        copy(gyroLimitRadPerSec = gyroLimitRadPerSec * factor, accelLimitMps2 = accelLimitMps2 * factor)

    companion object {
        const val DEFAULT_GYRO_LIMIT_RAD_PER_SEC = 0.10
        const val DEFAULT_ACCEL_LIMIT_MPS2 = 0.40
        const val DEFAULT_WINDOW_MS = 300L
        const val DEFAULT_SETTLE_MS = 400L
        const val DEFAULT_RELEASE_RATIO = 0.6
    }
}

/**
 * Decides whether the phone is being held still enough for the camera's picture to be trusted.
 *
 * Pure Kotlin, fed [MotionFrame]s from a [SensorSource] -- it never touches the hardware. It sits
 * **beside** the rep detectors, not inside them: the push-up pipeline asks [isStable] and simply
 * does not pass frames on while it is false, so the state machine in `com.repmate.engine` stays a
 * plain angle-to-reps machine.
 *
 * ## The reading
 * Over the last [StabilityConfig.windowMs] of frames it computes two numbers:
 * - the RMS of the gyroscope's angular speed ([gyroRmsRadPerSec]), which is how fast the camera is
 *   turning, and
 * - the standard deviation of the accelerometer's magnitude ([accelStdMps2]), which is how much the
 *   phone is being shaken or shifted.
 *
 * [level] is the larger of the two as a fraction of its limit, so 1.0 means "at a limit".
 *
 * ## The decision
 * Stable becomes false the moment [level] exceeds 1. It becomes true again only after [level] has
 * stayed under [StabilityConfig.releaseRatio] for [StabilityConfig.settleMs] without a break.
 * Because the window itself still holds the disturbance for [StabilityConfig.windowMs] after it
 * ends, counting resumes roughly a second after the phone stops moving.
 *
 * ## Edge cases
 * - **No frames** (no accelerometer, or the source has not started): the gate starts stable and
 *   stays that way, so a phone without sensors counts as before rather than never counting.
 * - **Too few frames in the window** to judge: the previous answer is kept.
 * - **One wild sample** moves the RMS enough to trip the gate. That is deliberate: a single glitch
 *   from the sensor is rare, and a genuine bump lasts several samples.
 *
 * Not thread safe; call it from one thread (the workout ViewModel's).
 */
class PhoneStabilityGate(config: StabilityConfig = StabilityConfig()) {

    /** The limits in force. Replaceable at runtime so they can be tuned on a phone. */
    var config: StabilityConfig = config

    private class Sample(val tMillis: Long, val gyroSquared: Double, val accelMagnitude: Double)

    private val window = ArrayDeque<Sample>()

    /** False while the phone is moving or has only just stopped. */
    var isStable: Boolean = true
        private set

    /** Latest gyroscope RMS in rad/s, or 0 before enough frames arrived. For display and logging. */
    var gyroRmsRadPerSec: Double = 0.0
        private set

    /** Latest accelerometer-magnitude standard deviation in m/s^2. For display and logging. */
    var accelStdMps2: Double = 0.0
        private set

    /** The larger of the two readings as a fraction of its limit; 1.0 is exactly at a limit. */
    var level: Double = 0.0
        private set

    private var calmSinceMs: Long? = null

    /** Feeds one sensor frame in and updates [isStable]. */
    fun onFrame(frame: MotionFrame) {
        val gyroSquared =
            (frame.gx * frame.gx + frame.gy * frame.gy + frame.gz * frame.gz).toDouble()
        val accelMagnitude =
            sqrt((frame.ax * frame.ax + frame.ay * frame.ay + frame.az * frame.az).toDouble())
        window.addLast(Sample(frame.tMillis, gyroSquared, accelMagnitude))
        while (window.isNotEmpty() && frame.tMillis - window.first().tMillis > config.windowMs) {
            window.removeFirst()
        }
        if (window.size < MIN_SAMPLES) return

        gyroRmsRadPerSec = sqrt(window.sumOf { it.gyroSquared } / window.size)
        val meanAccel = window.sumOf { it.accelMagnitude } / window.size
        accelStdMps2 = sqrt(window.sumOf { (it.accelMagnitude - meanAccel) * (it.accelMagnitude - meanAccel) } / window.size)
        level = max(gyroRmsRadPerSec / config.gyroLimitRadPerSec, accelStdMps2 / config.accelLimitMps2)

        if (isStable) {
            if (level > 1.0) {
                isStable = false
                calmSinceMs = null
            }
        } else if (level < config.releaseRatio) {
            val since = calmSinceMs ?: frame.tMillis.also { calmSinceMs = it }
            if (frame.tMillis - since >= config.settleMs) {
                isStable = true
                calmSinceMs = null
            }
        } else {
            calmSinceMs = null
        }
    }

    /** Forgets all history and goes back to stable, as at construction. */
    fun reset() {
        window.clear()
        isStable = true
        gyroRmsRadPerSec = 0.0
        accelStdMps2 = 0.0
        level = 0.0
        calmSinceMs = null
    }

    companion object {
        /** About 100 ms at 50 Hz: fewer than this is too little to call a standard deviation. */
        const val MIN_SAMPLES = 5
    }
}
