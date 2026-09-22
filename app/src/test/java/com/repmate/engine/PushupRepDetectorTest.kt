package com.repmate.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The counter sees only angles, so a synthetic angle series is a faithful input; what these tests
 * cannot say is whether a real person's elbow angle, as the pose detector reports it, looks like
 * this -- that is what the "first real run" cases below pin against an actual recorded session.
 */
class PushupRepDetectorTest {

    /** One rep as the angle a camera might report: straight at [top], down to [bottom] and back. */
    private fun rep(top: Double = 168.0, bottom: Double = 92.0, framesEachWay: Int = 12): List<Double> {
        val n = framesEachWay * 2
        return (0..n).map { i -> bottom + (top - bottom) * (1 + cos(2 * PI * i / n)) / 2 }
    }

    private fun sets(reps: Int, gapFrames: Int = 6, top: Double = 168.0) =
        List(reps) { rep(top = top) + List(gapFrames) { top } }.flatten()

    private fun PushupRepDetector.feed(angles: List<Double?>): PushupRepDetector {
        angles.forEach { update(it) }
        return this
    }

    @Test
    fun `ten clean reps count ten`() {
        assertEquals(10, PushupRepDetector().feed(sets(10)).count)
    }

    @Test
    fun `a rep is counted when the arm comes back to straight, not at the bottom`() {
        val detector = PushupRepDetector()
        val transitions = rep().mapNotNull { detector.update(it) }

        assertEquals(
            listOf(PushupRepDetector.Phase.UP, PushupRepDetector.Phase.DOWN, PushupRepDetector.Phase.UP),
            transitions.map { it.to },
        )
        assertEquals(listOf(false, false, true), transitions.map { it.repCompleted })
        assertEquals(1, detector.count)
    }

    @Test
    fun `a half rep that never gets bent enough counts nothing`() {
        // Straight, down only to 135 (above the 126.5 threshold), straight again: nine half reps.
        val half = List(9) { rep(bottom = 135.0) + List(6) { 168.0 } }.flatten()
        assertEquals(0, PushupRepDetector().feed(half).count)
    }

    @Test
    fun `a rep that stops short of straight counts nothing until it is straight`() {
        // Bent to 92, back up only to 140 (under the 152.6 threshold): the rep is not finished.
        val detector = PushupRepDetector().feed(rep() + rep(top = 140.0))
        assertEquals(1, detector.count, "the first rep counts; the second never got back to straight")
        assertEquals(PushupRepDetector.Phase.DOWN, detector.phase)
    }

    @Test
    fun `starting bent counts nothing until the arm has been straight once`() {
        val detector = PushupRepDetector().feed(List(20) { 95.0 } + List(20) { 100.0 })
        assertEquals(0, detector.count)
        assertEquals(PushupRepDetector.Phase.WAITING, detector.phase)

        detector.feed(List(10) { 165.0 })
        assertEquals(PushupRepDetector.Phase.UP, detector.phase)
        assertEquals(0, detector.count, "arriving at straight is the start of a rep, not the end of one")
    }

    @Test
    fun `an angle jittering around one threshold is not counted as reps`() {
        // 150-160 straddles the 152.6 straight threshold; without the wide gap this would rattle.
        val jitter = List(60) { i -> if (i % 2 == 0) 150.0 else 160.0 }
        assertEquals(0, PushupRepDetector().feed(List(10) { 168.0 } + jitter).count)
    }

    @Test
    fun `a single wild frame cannot count a rep by itself`() {
        // A landmark mislabelled for one frame: straight, one frame at 40 degrees, straight.
        val glitch = List(10) { 168.0 } + listOf(40.0) + List(10) { 168.0 }
        assertEquals(0, PushupRepDetector().feed(glitch).count)
    }

    @Test
    fun `frames with no angle are ignored and do not lose a rep in progress`() {
        val detector = PushupRepDetector()
        val withGaps = rep().flatMap { listOf(it, null, Double.NaN) }
        detector.feed(withGaps)
        assertEquals(1, detector.count)
    }

    @Test
    fun `reset returns to zero and waits for a straight arm again`() {
        val detector = PushupRepDetector().feed(sets(3))
        assertEquals(3, detector.count)

        detector.reset()
        assertEquals(0, detector.count)
        assertEquals(PushupRepDetector.Phase.WAITING, detector.phase)
        assertNull(detector.smoothedDegrees)
        assertEquals(1, detector.feed(rep()).count)
    }

    /**
     * The 11 clear reps of a real measured session (phone to the side), each as top to bottom, in
     * order. Taken from a logcat capture, not invented.
     */
    private val firstRealRun =
        listOf(
            170.0 to 121.0, 173.0 to 112.0, 164.0 to 116.0, 177.0 to 75.0, 153.0 to 104.0, 178.0 to 119.0,
            166.0 to 55.0, 175.0 to 113.0, 159.0 to 110.0, 178.0 to 115.0, 164.0 to 95.0,
        )

    private fun firstRealRunAngles() = firstRealRun.flatMap { (top, bottom) -> rep(top = top, bottom = bottom) + List(4) { top } }

    @Test
    fun `the defaults count all eleven reps of the first real run`() {
        assertEquals(11, PushupRepDetector().feed(firstRealRunAngles()).count)
    }

    @Test
    fun `the first guess of 155 and 105 would have missed most of them`() {
        // Pins why the defaults moved: most of those reps never got below 105.
        val old =
            PushupRepDetector(upAtOrAboveDegrees = 155.0, downAtOrBelowDegrees = 105.0)
                .feed(firstRealRunAngles()).count
        assertTrue(old <= 5, "155/105 counted $old of 11")
    }

    /** Straight, down to [bottom], and back up to a plateau at [top] only: no next rep to finish this one. */
    private fun repEndingAt(top: Double, start: Double = 168.0, bottom: Double = 92.0, frames: Int = 12): List<Double> {
        val down = (0..frames).map { i -> start + (bottom - start) * i / frames }
        val up = (1..frames).map { i -> bottom + (top - bottom) * i / frames }
        return down + up + List(6) { top }
    }

    @Test
    fun `a top of 153, as measured, counts and a top of 150 does not`() {
        // Pins the straight threshold (152.6) from both sides. Back to back, a shallow top is hidden by
        // the next rep's higher one finishing it, so each is tested on its own.
        val lockedOut = List(5) { 168.0 }
        assertEquals(1, PushupRepDetector().feed(lockedOut + repEndingAt(top = 153.0)).count)
        assertEquals(0, PushupRepDetector().feed(lockedOut + repEndingAt(top = 150.0)).count)
    }

    @Test
    fun `a window of 1 disables the filter`() {
        val raw = PushupRepDetector(medianWindow = 1)
        raw.update(168.0)
        raw.update(40.0)
        assertEquals(40.0, assertNotNull(raw.smoothedDegrees), 1e-9)
    }

    @Test
    fun `without the filter that same single wild frame does count a rep`() {
        // Pins why the median exists: the glitch test above is only meaningful if it can fail.
        val glitch = List(10) { 168.0 } + listOf(40.0) + List(10) { 168.0 }
        assertEquals(1, PushupRepDetector(medianWindow = 1).feed(glitch).count)
    }

    @Test
    fun `two wild frames in a row do pass the filter, which is the price of a window of 3`() {
        val twoFrames = List(10) { 168.0 } + listOf(40.0, 40.0) + List(10) { 168.0 }
        assertEquals(1, PushupRepDetector().feed(twoFrames).count)
    }

    @Test
    fun `thresholds and smoothing are validated`() {
        assertFailsWith<IllegalArgumentException> { PushupRepDetector(upAtOrAboveDegrees = 100.0, downAtOrBelowDegrees = 100.0) }
        assertFailsWith<IllegalArgumentException> { PushupRepDetector(medianWindow = 0) }
        assertFailsWith<IllegalArgumentException> { PushupRepDetector(medianWindow = 4) }
        assertTrue(PushupRepDetector.DEFAULT_DOWN_DEGREES < PushupRepDetector.DEFAULT_UP_DEGREES)
    }
}
