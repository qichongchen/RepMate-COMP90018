package com.repmate.pose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The property that matters is negative: once locked, nothing the arms do can change the choice.
 * Most tests below are about that, and about the failure modes that would quietly break it.
 */
class ArmLockTest {

    /** An arm whose three points share one depth; positions and likelihood do not matter here. */
    private fun arm(side: Arm.Side, z: Float) =
        Arm(side, Joint(0f, 0f, z, 1f), Joint(0f, 0f, z, 1f), Joint(0f, 0f, z, 1f))

    private fun ArmLock.feed(frames: Int, leftZ: Float?, rightZ: Float?) = repeat(frames) {
        observe(leftZ?.let { arm(Arm.Side.LEFT, it) }, rightZ?.let { arm(Arm.Side.RIGHT, it) })
    }

    @Test
    fun `it locks to the nearer arm once the settle window is full`() {
        val lock = ArmLock(settleFrames = 5)
        lock.feed(5, leftZ = -300f, rightZ = -400f)

        assertTrue(lock.isLocked)
        assertEquals(Arm.Side.RIGHT, lock.side, "smaller z is nearer the camera")
    }

    @Test
    fun `it is not locked before the window is full`() {
        val lock = ArmLock(settleFrames = 5)
        lock.feed(4, leftZ = -300f, rightZ = -400f)

        assertFalse(lock.isLocked)
        assertNull(lock.armFor(arm(Arm.Side.LEFT, 0f), arm(Arm.Side.RIGHT, 0f)), "nothing to read until locked")
    }

    @Test
    fun `one wild depth estimate cannot decide it`() {
        // Right is nearer in 6 of 7 frames; in one frame its depth estimate goes far the other way.
        val lock = ArmLock(settleFrames = 7)
        lock.feed(3, leftZ = -300f, rightZ = -400f)
        lock.feed(1, leftZ = -300f, rightZ = 900f)
        lock.feed(3, leftZ = -300f, rightZ = -400f)

        assertEquals(Arm.Side.RIGHT, lock.side, "the median ignores the outlier a mean would follow")
    }

    @Test
    fun `once locked it never switches, however much nearer the other arm becomes`() {
        val lock = ArmLock(settleFrames = 5)
        lock.feed(5, leftZ = -300f, rightZ = -400f)
        assertEquals(Arm.Side.RIGHT, lock.side)

        // The left arm now reads far nearer, for a long time: the old per-frame rule would switch.
        lock.feed(300, leftZ = -900f, rightZ = 200f)

        assertEquals(Arm.Side.RIGHT, lock.side)
        val left = arm(Arm.Side.LEFT, -900f)
        val right = arm(Arm.Side.RIGHT, 200f)
        assertSame(right, lock.armFor(left, right))
    }

    @Test
    fun `a locked arm that goes missing gives nothing, not the other arm`() {
        val lock = ArmLock(settleFrames = 5)
        lock.feed(5, leftZ = -300f, rightZ = -400f)

        assertNull(lock.armFor(arm(Arm.Side.LEFT, -300f), null), "falling back would reintroduce the switch")
        assertNull(lock.armFor(null, null))
    }

    @Test
    fun `frames with no pose are not evidence`() {
        val lock = ArmLock(settleFrames = 3)
        lock.feed(50, leftZ = null, rightZ = null)
        assertFalse(lock.isLocked)
        assertEquals(0, lock.framesObserved)

        lock.feed(3, leftZ = -300f, rightZ = -400f)
        assertTrue(lock.isLocked)
    }

    @Test
    fun `an arm seen alone is the one locked`() {
        val onlyRight = ArmLock(settleFrames = 3).also { it.feed(3, leftZ = null, rightZ = 50f) }
        assertEquals(Arm.Side.RIGHT, onlyRight.side)

        val onlyLeft = ArmLock(settleFrames = 3).also { it.feed(3, leftZ = 50f, rightZ = null) }
        assertEquals(Arm.Side.LEFT, onlyLeft.side)
    }

    @Test
    fun `an exact tie goes to the left arm`() {
        val lock = ArmLock(settleFrames = 3)
        lock.feed(3, leftZ = -200f, rightZ = -200f)
        assertEquals(Arm.Side.LEFT, lock.side)
    }

    @Test
    fun `reset forgets the lock and chooses afresh`() {
        val lock = ArmLock(settleFrames = 3)
        lock.feed(3, leftZ = 0f, rightZ = -1000f)
        assertEquals(Arm.Side.RIGHT, lock.side)

        lock.reset()
        assertFalse(lock.isLocked)
        assertNull(lock.lockedDepths)
        assertEquals(0, lock.framesObserved)

        // On its own this favours the left arm; mixed with the old evidence above it would favour the
        // right, so it only comes out left if reset really discarded what it had collected.
        lock.feed(3, leftZ = -400f, rightZ = -300f)
        assertEquals(Arm.Side.LEFT, lock.side, "the old evidence must not leak into the new decision")
    }

    @Test
    fun `the depths the decision was made on are kept for logging`() {
        val lock = ArmLock(settleFrames = 3)
        lock.feed(3, leftZ = -300f, rightZ = -400f)
        assertEquals(-300f to -400f, assertNotNull(lock.lockedDepths))
    }

    @Test
    fun `the settle window must be at least one frame`() {
        assertFailsWith<IllegalArgumentException> { ArmLock(settleFrames = 0) }
    }
}
