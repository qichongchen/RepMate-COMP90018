package com.repmate.pose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountingGateTest {

    private fun arm(side: Arm.Side, likelihood: Float, z: Float = 0f) =
        Arm(side, Joint(0f, 0f, z, likelihood), Joint(0f, 0f, z, likelihood), Joint(0f, 0f, z, likelihood))

    // --- countingStatus -----------------------------------------------------------------------

    @Test
    fun `everything in order means counting`() {
        assertEquals(CountingStatus.COUNTING, countingStatus(phoneStable = true, Tracking.TRACKING, armLocked = true))
    }

    @Test
    fun `a moving phone outranks every other reason`() {
        for (tracking in Tracking.entries) {
            for (locked in listOf(true, false)) {
                assertEquals(CountingStatus.PHONE_MOVING, countingStatus(false, tracking, locked), "$tracking locked=$locked")
            }
        }
    }

    @Test
    fun `no person and low confidence are reported whether or not an arm is locked`() {
        for (locked in listOf(true, false)) {
            assertEquals(CountingStatus.NO_PERSON, countingStatus(true, Tracking.NO_PERSON, locked))
            assertEquals(CountingStatus.LOW_CONFIDENCE, countingStatus(true, Tracking.LOW_CONFIDENCE, locked))
        }
    }

    @Test
    fun `a visible person with no arm chosen yet is finding the arm`() {
        assertEquals(CountingStatus.FINDING_ARM, countingStatus(true, Tracking.TRACKING, armLocked = false))
    }

    @Test
    fun `only COUNTING counts reps`() {
        for (status in CountingStatus.entries) {
            assertEquals(status == CountingStatus.COUNTING, status.countsReps, "$status")
        }
    }

    // --- frameTracking ------------------------------------------------------------------------

    @Test
    fun `no arms at all is no person`() {
        assertEquals(Tracking.NO_PERSON, frameTracking(null, null, null, 0.5f, null))
        assertEquals(Tracking.NO_PERSON, frameTracking(null, null, Arm.Side.LEFT, 0.5f, Tracking.TRACKING))
    }

    @Test
    fun `before an arm is locked the more confident arm is judged`() {
        val left = arm(Arm.Side.LEFT, 0.2f)
        val right = arm(Arm.Side.RIGHT, 0.9f)
        assertEquals(Tracking.TRACKING, frameTracking(left, right, null, 0.5f, null))
        assertEquals(Tracking.TRACKING, frameTracking(right, left, null, 0.5f, null))
    }

    @Test
    fun `once locked only the locked arm is judged, even when the other is clearer`() {
        val left = arm(Arm.Side.LEFT, 0.2f)
        val right = arm(Arm.Side.RIGHT, 0.95f)
        assertEquals(Tracking.LOW_CONFIDENCE, frameTracking(left, right, Arm.Side.LEFT, 0.5f, null))
        assertEquals(Tracking.TRACKING, frameTracking(left, right, Arm.Side.RIGHT, 0.5f, null))
    }

    @Test
    fun `a missing locked arm with the other present is low confidence, not no person`() {
        val right = arm(Arm.Side.RIGHT, 0.95f)
        assertEquals(Tracking.LOW_CONFIDENCE, frameTracking(null, right, Arm.Side.LEFT, 0.5f, Tracking.TRACKING))
    }

    @Test
    fun `an established tracking survives a dip that a new one would not`() {
        val dip = arm(Arm.Side.LEFT, 0.4f) // between the exit level (0.3) and the entry level (0.5)
        assertEquals(Tracking.TRACKING, frameTracking(dip, null, Arm.Side.LEFT, 0.5f, Tracking.TRACKING))
        assertEquals(Tracking.LOW_CONFIDENCE, frameTracking(dip, null, Arm.Side.LEFT, 0.5f, Tracking.LOW_CONFIDENCE))
        assertFalse(Tracking.TRACKING == frameTracking(dip, null, Arm.Side.LEFT, 0.5f, null))
        assertTrue(frameTracking(arm(Arm.Side.LEFT, 0.2f), null, Arm.Side.LEFT, 0.5f, Tracking.TRACKING) == Tracking.LOW_CONFIDENCE)
    }
}
