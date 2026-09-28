package com.repmate.sensors

import com.repmate.engine.MotionFrame
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PhoneStabilityGate] takes plain [MotionFrame]s, so it is tested with synthetic streams at the
 * 50 Hz the real sensor source delivers. Each test pins one behaviour a real phone depends on.
 */
class PhoneStabilityGateTest {

    private val stepMs = 20L

    /** A frame with gravity along z, plus whatever motion the test asks for. */
    private fun frame(
        t: Long,
        ax: Float = 0f, ay: Float = 0f, az: Float = 9.81f,
        gx: Float = 0f, gy: Float = 0f, gz: Float = 0f,
    ) = MotionFrame(t, ax, ay, az, gx, gy, gz)

    /** Feeds frames from [fromMs] up to but not including [untilMs]; returns the next time to use. */
    private fun PhoneStabilityGate.feed(fromMs: Long, untilMs: Long, make: (Long) -> MotionFrame): Long {
        var t = fromMs
        while (t < untilMs) {
            onFrame(make(t))
            t += stepMs
        }
        return t
    }

    private fun PhoneStabilityGate.still(fromMs: Long, untilMs: Long): Long = feed(fromMs, untilMs) { frame(it) }

    private fun PhoneStabilityGate.spin(fromMs: Long, untilMs: Long, rate: Float = 0.5f): Long =
        feed(fromMs, untilMs) { frame(it, gz = rate) }

    @Test
    fun `a gate that has seen nothing is stable`() {
        assertTrue(PhoneStabilityGate().isStable)
    }

    @Test
    fun `a phone lying still is stable`() {
        val gate = PhoneStabilityGate()
        gate.feed(0, 2_000) { t ->
            // Sensor noise: a hundredth of a m/s^2 on the accelerometer, a few thousandths of a rad/s.
            val wobble = if ((t / stepMs) % 2 == 0L) 0.01f else -0.01f
            frame(t, az = 9.81f + wobble, gx = 0.003f * wobble * 100)
        }
        assertTrue(gate.isStable)
        assertTrue(gate.level < 0.2, "level was ${gate.level}")
    }

    @Test
    fun `rotating faster than the gyro limit makes it unstable`() {
        val gate = PhoneStabilityGate()
        val t = gate.still(0, 1_000)
        assertTrue(gate.isStable)
        gate.spin(t, t + 500)
        assertFalse(gate.isStable)
    }

    @Test
    fun `shaking beyond the accelerometer limit makes it unstable with no rotation at all`() {
        val gate = PhoneStabilityGate()
        val t = gate.still(0, 1_000)
        gate.feed(t, t + 500) { time ->
            val swing = if ((time / stepMs) % 2 == 0L) 1.5f else -1.5f
            frame(time, az = 9.81f + swing)
        }
        assertFalse(gate.isStable)
        assertEquals(0.0, gate.gyroRmsRadPerSec, 1e-9)
    }

    @Test
    fun `rotation about any one axis trips the gyro limit`() {
        val axes = listOf<(Long) -> MotionFrame>(
            { frame(it, gx = 0.5f) },
            { frame(it, gy = 0.5f) },
            { frame(it, gz = 0.5f) },
        )
        for ((index, make) in axes.withIndex()) {
            val gate = PhoneStabilityGate()
            gate.feed(0, 500, make)
            assertFalse(gate.isStable, "axis $index did not trip the gate")
        }
    }

    @Test
    fun `shaking along any one axis trips the accelerometer limit`() {
        fun swing(t: Long) = if ((t / stepMs) % 2 == 0L) 3f else -3f
        val axes = listOf<(Long) -> MotionFrame>(
            { frame(it, ax = 9.81f + swing(it), az = 0f) },
            { frame(it, ay = 9.81f + swing(it), az = 0f) },
            { frame(it, az = 9.81f + swing(it)) },
        )
        for ((index, make) in axes.withIndex()) {
            val gate = PhoneStabilityGate()
            gate.feed(0, 500, make)
            assertFalse(gate.isStable, "axis $index did not trip the gate")
        }
    }

    @Test
    fun `a slow steady rotation under the limit is still stable`() {
        val gate = PhoneStabilityGate()
        gate.spin(0, 2_000, rate = 0.05f)
        assertTrue(gate.isStable)
    }

    @Test
    fun `gravity along any axis and a biased reading make no difference`() {
        for ((ax, ay, az) in listOf(Triple(9.81f, 0f, 0f), Triple(0f, -9.81f, 0f), Triple(6f, 6f, 5.4f), Triple(0f, 0f, 9.5f))) {
            val gate = PhoneStabilityGate()
            gate.feed(0, 2_000) { frame(it, ax, ay, az) }
            assertTrue(gate.isStable, "unstable with gravity ($ax, $ay, $az)")
            assertEquals(0.0, gate.accelStdMps2, 1e-4)
        }
    }

    @Test
    fun `a bump only a few samples long is caught`() {
        val gate = PhoneStabilityGate()
        var t = gate.still(0, 1_000)
        repeat(5) { gate.onFrame(frame(t, gz = 2f)); t += stepMs }
        assertFalse(gate.isStable)
    }

    @Test
    fun `it takes settleMs of calm, not just a quiet window, to be trusted again`() {
        val gate = PhoneStabilityGate(StabilityConfig(settleMs = 2_000))
        var t = gate.still(0, 1_000)
        t = gate.spin(t, t + 500)
        assertFalse(gate.isStable)

        // Well past the 300 ms window the readings are calm, but settleMs has not elapsed.
        t = gate.still(t, t + 1_000)
        assertTrue(gate.level < 0.1)
        assertFalse(gate.isStable, "became stable before settleMs of calm")

        gate.still(t, t + 1_500)
        assertTrue(gate.isStable)
    }

    @Test
    fun `it is trusted again about a second after the phone stops moving with the defaults`() {
        val gate = PhoneStabilityGate()
        var t = gate.still(0, 1_000)
        t = gate.spin(t, t + 500)
        assertFalse(gate.isStable)
        gate.still(t, t + 1_200)
        assertTrue(gate.isStable)
    }

    @Test
    fun `a disturbance during the settle period restarts the wait`() {
        val gate = PhoneStabilityGate(StabilityConfig(settleMs = 1_000))
        var t = gate.still(0, 1_000)
        t = gate.spin(t, t + 500)
        t = gate.still(t, t + 800) // calm for 800 of the 1000 ms needed
        t = gate.spin(t, t + 200)
        t = gate.still(t, t + 700) // calm again, but the count started over
        assertFalse(gate.isStable, "the earlier calm was counted after the interruption")
        gate.still(t, t + 1_000)
        assertTrue(gate.isStable)
    }

    @Test
    fun `a reading between the release level and the limit neither trips nor releases the gate`() {
        val config = StabilityConfig()
        val between = (config.gyroLimitRadPerSec * 0.8).toFloat() // above 0.6 x limit, under the limit

        val stable = PhoneStabilityGate(config)
        stable.spin(0, 3_000, rate = between)
        assertTrue(stable.isStable, "0.8 x limit tripped the gate")

        val unstable = PhoneStabilityGate(config)
        var t = unstable.still(0, 500)
        t = unstable.spin(t, t + 500)
        assertFalse(unstable.isStable)
        unstable.spin(t, t + 3_000, rate = between)
        assertFalse(unstable.isStable, "0.8 x limit released the gate")
    }

    @Test
    fun `the reading forgets what is older than the window`() {
        val gate = PhoneStabilityGate()
        var t = gate.still(0, 500)
        t = gate.spin(t, t + 300)
        assertTrue(gate.gyroRmsRadPerSec > 0.3)
        gate.still(t, t + 1_000)
        assertEquals(0.0, gate.gyroRmsRadPerSec, 1e-9)
    }

    @Test
    fun `too few frames to judge keep the previous answer`() {
        val gate = PhoneStabilityGate()
        var t = 0L
        repeat(PhoneStabilityGate.MIN_SAMPLES - 1) { gate.onFrame(frame(t, gz = 5f)); t += stepMs }
        assertTrue(gate.isStable)
        gate.onFrame(frame(t, gz = 5f))
        assertFalse(gate.isStable)
    }

    @Test
    fun `scaling the limits up admits a movement the defaults reject`() {
        val rate = 0.15f // 1.5 x the default gyro limit
        val strict = PhoneStabilityGate()
        strict.spin(0, 1_000, rate)
        assertFalse(strict.isStable)

        val loose = PhoneStabilityGate(StabilityConfig().scaledBy(2.0))
        loose.spin(0, 1_000, rate)
        assertTrue(loose.isStable)
    }

    @Test
    fun `the limits can be changed on a running gate`() {
        val gate = PhoneStabilityGate()
        gate.spin(0, 500, rate = 0.15f)
        assertFalse(gate.isStable)
        gate.config = gate.config.scaledBy(3.0)
        gate.spin(500, 3_000, rate = 0.15f)
        assertTrue(gate.isStable)
    }

    @Test
    fun `reset goes back to stable and clears the readings`() {
        val gate = PhoneStabilityGate()
        gate.spin(0, 1_000)
        assertFalse(gate.isStable)
        gate.reset()
        assertTrue(gate.isStable)
        assertEquals(0.0, gate.level, 0.0)
        assertEquals(0.0, gate.gyroRmsRadPerSec, 0.0)
    }

    @Test
    fun `a slow back and forth sway of the accelerometer under the limit is stable`() {
        val gate = PhoneStabilityGate()
        gate.feed(0, 3_000) { t -> frame(t, az = 9.81f + 0.1f * sin(2 * PI * t / 1_000.0).toFloat()) }
        assertTrue(gate.isStable)
    }
}
