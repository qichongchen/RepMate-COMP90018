package com.repmate.pose

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The camera and pose detector cannot run in a JVM test, but the arithmetic that turns landmarks
 * into an elbow angle and a tracking label can. Those numbers are what
 * [com.repmate.engine.PushupRepDetector] and the workout screen actually consume, so they are
 * what is pinned here.
 */
class PoseGeometryTest {

    private fun angle(sx: Float, sy: Float, ex: Float, ey: Float, wx: Float, wy: Float): Double =
        assertNotNull(elbowAngleDegrees(sx, sy, ex, ey, wx, wy))

    /** An arm whose three points share one likelihood; positions and depth do not matter here. */
    private fun arm(side: Arm.Side, likelihood: Float) =
        Arm(side, Joint(0f, 0f, 0f, likelihood), Joint(0f, 0f, 0f, likelihood), Joint(0f, 0f, 0f, likelihood))

    // --- the elbow angle ----------------------------------------------------------------------

    @Test
    fun `a right angle reads 90`() {
        assertEquals(90.0, angle(0f, -100f, 0f, 0f, 100f, 0f), 1e-9)
    }

    @Test
    fun `a straight arm reads 180 and a fully folded arm reads 0`() {
        assertEquals(180.0, angle(0f, -100f, 0f, 0f, 0f, 100f), 1e-9)
        assertEquals(0.0, angle(0f, -100f, 0f, 0f, 0f, -50f), 1e-9)
    }

    @Test
    fun `a 45 degree bend reads 45`() {
        assertEquals(45.0, angle(100f, 0f, 0f, 0f, 100f, 100f), 1e-6)
    }

    @Test
    fun `the reading is the interior angle when the two directions straddle the wrap-around`() {
        // Upper arm at 170 degrees, forearm at -170: the raw difference is 340; the bend is 20.
        fun at(degrees: Double) = Pair(
            (100 * cos(Math.toRadians(degrees))).toFloat(),
            (100 * sin(Math.toRadians(degrees))).toFloat(),
        )
        val (sx, sy) = at(170.0)
        val (wx, wy) = at(-170.0)
        assertEquals(20.0, angle(sx, sy, 0f, 0f, wx, wy), 1e-4)
    }

    @Test
    fun `a mirrored image and swapping shoulder with wrist give the same angle`() {
        val original = angle(100f, 0f, 0f, 0f, 60f, 80f)
        assertEquals(original, angle(-100f, 0f, 0f, 0f, -60f, 80f), 1e-9)
        assertEquals(original, angle(60f, 80f, 0f, 0f, 100f, 0f), 1e-9)
    }

    @Test
    fun `coincident landmarks give no angle rather than a made-up one`() {
        assertNull(elbowAngleDegrees(0f, -100f, 0f, 0f, 0f, 0f), "wrist on the elbow")
        assertNull(elbowAngleDegrees(0f, 0f, 0f, 0f, 100f, 0f), "shoulder on the elbow")
    }

    // --- tracking -------------------------------------------------------------------------

    @Test
    fun `the tracking label does not rattle while confidence hovers around the gate`() {
        var state: Tracking? = null
        val seen = mutableListOf<Tracking>()
        for (likelihood in listOf(0.9f, 0.55f, 0.45f, 0.55f, 0.42f, 0.5f, 0.38f)) {
            state = trackingState(arm(Arm.Side.LEFT, likelihood), 0.5f, previous = state)
            seen += state
        }
        assertEquals(List(7) { Tracking.TRACKING }, seen)
    }

    @Test
    fun `the tracking label drops once confidence really falls, and needs the full gate to recover`() {
        val low = arm(Arm.Side.LEFT, 0.2f)
        val middling = arm(Arm.Side.LEFT, 0.45f)
        val high = arm(Arm.Side.LEFT, 0.9f)
        assertEquals(Tracking.LOW_CONFIDENCE, trackingState(low, 0.5f, previous = Tracking.TRACKING))
        assertEquals(Tracking.LOW_CONFIDENCE, trackingState(middling, 0.5f, previous = Tracking.LOW_CONFIDENCE))
        assertEquals(Tracking.TRACKING, trackingState(high, 0.5f, previous = Tracking.LOW_CONFIDENCE))
        assertEquals(Tracking.NO_PERSON, trackingState(null, 0.5f, previous = Tracking.TRACKING))
    }

    @Test
    fun `tracking state follows presence and the confidence threshold`() {
        assertEquals(Tracking.NO_PERSON, trackingState(null, 0.5f))
        assertEquals(Tracking.LOW_CONFIDENCE, trackingState(arm(Arm.Side.LEFT, 0.49f), 0.5f))
        assertEquals(Tracking.TRACKING, trackingState(arm(Arm.Side.LEFT, 0.5f), 0.5f))
    }
}
