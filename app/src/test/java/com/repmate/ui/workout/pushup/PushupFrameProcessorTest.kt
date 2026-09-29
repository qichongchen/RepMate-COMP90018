package com.repmate.ui.workout.pushup

import com.repmate.engine.PushupRepDetector
import com.repmate.pose.Arm
import com.repmate.pose.CountingStatus
import com.repmate.pose.Joint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An arm whose elbow reads [angle] degrees; shoulder above the elbow, wrist swung round from it. */
private fun armAt(side: Arm.Side, angle: Double, likelihood: Float): Arm {
    val a = Math.toRadians(angle)
    return Arm(
        side,
        Joint(0f, -100f, 0f, likelihood),
        Joint(0f, 0f, 0f, likelihood),
        Joint((100 * sin(a)).toFloat(), (-100 * cos(a)).toFloat(), 0f, likelihood),
    )
}

/**
 * The gating around the rep detector, run with synthetic arms. [PushupRepDetector] itself is tested
 * on its own; what is pinned here is *when it is allowed to see an angle*.
 */
class PushupFrameProcessorTest {

    private val straight = 170.0
    private val bent = 100.0

    /** Drives one processor at 25 frames a second and tallies the reps it reports. */
    private class Driver {
        val processor = PushupFrameProcessor()
        var nowMs = 0L
        var reps = 0
        var last: PushupFrameProcessor.Result? = null

        fun frame(
            angle: Double?,
            stable: Boolean = true,
            likelihood: Float = 0.95f,
            dropLeft: Boolean = false,
        ) {
            val l = if (dropLeft) null else angle?.let { armAt(Arm.Side.LEFT, it, likelihood) }
            val r = angle?.let { armAt(Arm.Side.RIGHT, it, likelihood) }
            val result = processor.process(l, r, stable, nowMs)
            if (result.repCompleted) reps++
            last = result
            nowMs += 40
        }
    }

    private fun Driver.hold(angle: Double?, frames: Int, stable: Boolean = true, likelihood: Float = 0.95f) =
        repeat(frames) { frame(angle, stable, likelihood) }

    /** Enough steady confident frames for the arm lock to settle. */
    private fun Driver.lockOn() = hold(straight, 15)

    /** One push-up: straight, bent, straight. */
    private fun Driver.pushUp(stable: Boolean = true, likelihood: Float = 0.95f) {
        hold(straight, 5, stable, likelihood)
        hold(bent, 5, stable, likelihood)
        hold(straight, 5, stable, likelihood)
    }

    @Test
    fun `a steady confident set is counted - the baseline the other tests bend`() {
        val d = Driver()
        d.lockOn()
        assertEquals(CountingStatus.COUNTING, d.last?.status)
        d.pushUp()
        d.pushUp()
        assertEquals(2, d.reps)
    }

    @Test
    fun `no rep is counted while the phone is moving`() {
        val d = Driver()
        d.lockOn()
        d.pushUp(stable = false)
        assertEquals(0, d.reps)
        assertEquals(CountingStatus.PHONE_MOVING, d.last?.status)
    }

    @Test
    fun `counting resumes as soon as the phone is steady again`() {
        val d = Driver()
        d.lockOn()
        d.pushUp(stable = false)
        d.pushUp()
        assertEquals(1, d.reps)
    }

    @Test
    fun `the arm is not locked from frames taken while the phone moves`() {
        val d = Driver()
        d.hold(straight, 40, stable = false)
        assertFalse(d.last!!.armLocked)
        d.hold(straight, 14)
        assertFalse(d.last!!.armLocked)
        d.hold(straight, 1)
        assertTrue(d.last!!.armLocked)
    }

    @Test
    fun `the arm is not locked from frames where nobody is clearly there`() {
        val d = Driver()
        d.hold(straight, 40, likelihood = 0.2f)
        assertFalse(d.last!!.armLocked)
        d.hold(straight, 15)
        assertTrue(d.last!!.armLocked)
    }

    @Test
    fun `nobody in frame is reported and counts nothing`() {
        val d = Driver()
        d.lockOn()
        d.hold(null, 30)
        assertEquals(CountingStatus.NO_PERSON, d.last?.status)
        assertEquals(0, d.reps)
    }

    @Test
    fun `an arm below the confidence level is reported and counts nothing`() {
        val d = Driver()
        d.lockOn()
        d.pushUp(likelihood = 0.2f)
        assertEquals(CountingStatus.LOW_CONFIDENCE, d.last?.status)
        assertEquals(0, d.reps)
    }

    @Test
    fun `a dip between the exit and entry confidence does not stop an established count`() {
        val d = Driver()
        d.lockOn()
        d.pushUp(likelihood = 0.4f)
        assertEquals(1, d.reps)
    }

    @Test
    fun `the locked arm going missing is low confidence even though the other arm is there`() {
        val d = Driver()
        d.lockOn() // ties lock the left arm
        d.frame(straight, dropLeft = true)
        assertEquals(CountingStatus.LOW_CONFIDENCE, d.last?.status)
    }

    @Test
    fun `a short pause in the middle of a rep still lets that rep count`() {
        val d = Driver()
        d.lockOn()
        d.hold(straight, 5)
        d.hold(bent, 5)
        d.hold(bent, 25, stable = false) // one second: a bump
        d.hold(straight, 5)
        assertEquals(1, d.reps)
    }

    @Test
    fun `a long pause starts over instead of finishing the old rep`() {
        val d = Driver()
        d.lockOn()
        d.hold(straight, 5)
        d.hold(bent, 5)
        d.hold(null, 100) // four seconds with nobody in frame
        d.hold(straight, 5)
        assertEquals(0, d.reps, "a rep was finished after the person left and came back")
        assertEquals(PushupRepDetector.Phase.WAITING, d.last?.phase)
        assertFalse(d.last!!.armLocked)
    }

    @Test
    fun `a long pause and a fresh lock still do not finish the rep left open before it`() {
        val d = Driver()
        d.lockOn()
        d.hold(straight, 5)
        d.hold(bent, 5)
        d.hold(null, 100)
        d.hold(straight, 40)
        assertEquals(0, d.reps)
    }

    @Test
    fun `a brief pause earlier in the set does not count against a later stretch of counting`() {
        val d = Driver()
        d.lockOn()
        d.hold(straight, 10, stable = false) // 0.4 s, nowhere near stale
        repeat(6) { d.pushUp() } // 3.6 s of counting: longer than the stale limit
        assertEquals(6, d.reps)
    }

    @Test
    fun `after a long pause it locks again and counts normally`() {
        val d = Driver()
        d.lockOn()
        d.hold(null, 100)
        d.lockOn()
        d.pushUp()
        assertEquals(1, d.reps)
    }

    @Test
    fun `reset forgets the arm lock and any rep in progress`() {
        val d = Driver()
        d.lockOn()
        d.hold(bent, 5)
        d.processor.reset()
        d.hold(straight, 1)
        assertFalse(d.last!!.armLocked)
        assertEquals(PushupRepDetector.Phase.WAITING, d.last?.phase)
    }
}
