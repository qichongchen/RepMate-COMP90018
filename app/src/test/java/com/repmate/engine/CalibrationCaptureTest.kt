package com.repmate.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [SquatRepDetector.forCalibrationCapture], the detector a calibration set is
 * captured with, against all four recorded squat traces.
 *
 * Half of this file is about what capture must **reject**. Loosening a guard to solve the
 * bootstrap problem is only safe if the reps it lets through are reps; each rejection test
 * below names a specific non-rep in a specific recording, first proves it is really in the
 * signal (so the test cannot pass vacuously), then proves capture does not count it.
 *
 * Expected values are literals, for the reason given in [CalibrationProfileTest]: a test that
 * recomputes its expectation from the constant under test follows that constant wherever it
 * goes.
 */
class CalibrationCaptureTest {

    private val squatTraces: List<LabelledTrace> by lazy {
        TraceLibrary.loadAll().filter { it.expectation.exercise == ExerciseType.SQUAT }
    }

    private fun trace(name: String): LabelledTrace = squatTraces.single { it.name == name }

    private fun capture(frames: List<MotionFrame>): List<RepEvent> =
        SquatRepDetector.forCalibrationCapture().processAll(frames)

    /** The same frames replayed faster: every timestamp multiplied by [factor]. */
    private fun compressed(frames: List<MotionFrame>, factor: Double): List<MotionFrame> =
        frames.map { it.copy(tMillis = (it.tMillis * factor).toLong()) }

    /** The raw burst starting within 50 ms of [startMs], before any guard has judged it. */
    private fun burstNear(trace: LabelledTrace, startMs: Long): Burst =
        assertNotNull(
            BurstDetector().processAll(trace.frames).singleOrNull { kotlin.math.abs(it.startMs - startMs) <= 50 },
            "${trace.name}: expected a raw burst near $startMs ms — has the recording changed?"
        )

    private fun List<RepEvent>.overlapping(startMs: Long, endMs: Long) =
        filter { it.startMs < endMs && it.endMs > startMs }

    @Test
    fun `all four squat traces are present`() {
        assertEquals(
            listOf("squat_10_fast", "squat_10_hit", "squat_10_lisa", "squat_10_pocket"),
            squatTraces.map { it.name }
        )
    }

    // --- What capture accepts ----------------------------------------------------------

    @Test
    fun `capture counts exactly the ground-truth reps in every set window`() {
        for (trace in squatTraces) {
            assertEquals(10, capture(trace.setWindow()).size, "${trace.name}: set window")
        }
    }

    @Test
    fun `every trace calibrates from its first five captured reps, and the profile then counts the set`() {
        // The on-device proof in miniature: calibrate on five reps, then count all ten.
        for (trace in squatTraces) {
            val firstFive = capture(trace.setWindow()).take(CalibrationProfile.REQUIRED_SAMPLES)
            val outcome = CalibrationProfile.evaluate(firstFive)
            val accepted = assertIs<CalibrationOutcome.Accepted>(outcome, "${trace.name}: $outcome")
            assertEquals(
                10,
                SquatRepDetector(accepted.profile).processAll(trace.setWindow()).size,
                "${trace.name}: calibrated detector over the set window"
            )
        }
    }

    @Test
    fun `capture catches fast reps that the default duration floor drops`() {
        // The bootstrap problem on real signal: the same sets replayed at 0.6x time put reps in
        // the 417-514 ms range live testing missed. The defaults lose some or all of them, so a
        // calibration captured with the defaults would never see the user's real pace.
        val expectedDefaultCounts = mapOf("squat_10_fast" to 0, "squat_10_pocket" to 5)
        for ((name, defaultCount) in expectedDefaultCounts) {
            val fast = compressed(trace(name).setWindow(), 0.6)
            val captured = capture(fast)

            assertTrue(
                captured.minOf { it.endMs - it.startMs } < SquatRepDetector.DEFAULT_MIN_REP_DURATION_MS,
                "$name: compression should produce reps under the default floor, or this proves nothing"
            )
            assertEquals(defaultCount, SquatRepDetector().processAll(fast).size, "$name: defaults at 0.6x")
            assertEquals(10, captured.size, "$name: capture at 0.6x")
        }
        // Hit and Lisa are not used here: compressing time also shrinks the gaps between
        // bursts, and on those two it merges a pre-set handling burst into the first rep —
        // a compression artefact, not a test of the duration floor.
    }

    // --- What capture rejects ----------------------------------------------------------

    @Test
    fun `capture detects nothing in the declared quiet windows`() {
        val quiet = squatTraces.mapNotNull { trace -> trace.quietWindow()?.let { trace.name to it } }
        assertEquals(listOf("squat_10_hit", "squat_10_pocket"), quiet.map { it.first })
        for ((name, frames) in quiet) {
            assertEquals(0, capture(frames).size, "$name: quiet window")
        }
    }

    @Test
    fun `capture rejects Hit's in-set wobble`() {
        val hit = trace("squat_10_hit")

        // The wobble is really there, and long enough that the capture duration floor alone
        // would let it through: only the amplitude floor stands between it and the set.
        val wobble = burstNear(hit, 63477L)
        assertTrue(wobble.durationMs >= CalibrationProfile.MIN_REP_DURATION_FLOOR_MS, "wobble lasts ${wobble.durationMs} ms")
        assertTrue(wobble.amplitude in 0.80f..0.90f, "wobble swings ${wobble.amplitude}")

        assertEquals(
            emptyList(),
            capture(hit.setWindow()).overlapping(wobble.startMs, wobble.endMs),
            "capture counted Hit's wobble"
        )
    }

    @Test
    fun `capture rejects Lisa's post-rep rebounds, which a shorter cooldown would admit`() {
        val lisa = trace("squat_10_lisa")

        // Both rebounds are long and strong enough to pass the capture duration and amplitude
        // floors: the cooldown is the only guard rejecting them.
        val rebounds = listOf(burstNear(lisa, 8799L), burstNear(lisa, 22003L))
        for (rebound in rebounds) {
            assertTrue(rebound.durationMs >= CalibrationProfile.MIN_REP_DURATION_FLOOR_MS, "rebound $rebound")
            assertTrue(rebound.amplitude >= SquatRepDetector.DEFAULT_MIN_AMPLITUDE, "rebound $rebound")
        }

        val captured = capture(lisa.setWindow())
        for (rebound in rebounds) {
            assertEquals(emptyList(), captured.overlapping(rebound.startMs, rebound.endMs), "capture counted $rebound")
        }

        // Pins the cost of "loosening every guard": at a 200 ms cooldown both rebounds count,
        // and the first five reps then span 4.5x in amplitude, so the whole calibration is
        // rejected rather than merely polluted.
        val looser = SquatRepDetector(minRepDurationMs = CalibrationProfile.MIN_REP_DURATION_FLOOR_MS, cooldownMs = 200L)
            .processAll(lisa.setWindow())
        assertEquals(12, looser.size, "a 200 ms cooldown should admit both rebounds")
        val outcome = CalibrationProfile.evaluate(looser.take(CalibrationProfile.REQUIRED_SAMPLES))
        assertEquals(
            CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT,
            assertIs<CalibrationOutcome.Rejected>(outcome).reason
        )
    }

    @Test
    fun `everything capture adds over the defaults falls outside the set, which is why capture waits for a countdown`() {
        // Over whole recordings capture reports a few more events than the defaults. Every one
        // of them is phone handling before or after the set, never inside it — so the lower
        // floor costs nothing once the capture window excludes the pocketing.
        var extras = 0
        for (trace in squatTraces) {
            val defaults = SquatRepDetector().processAll(trace.frames).map { it.startMs }.toSet()
            val added = capture(trace.frames).filter { it.startMs !in defaults }
            extras += added.size
            val range = trace.expectation.setStartMs..trace.expectation.setEndMs
            assertEquals(
                emptyList(),
                added.filter { it.startMs in range },
                "${trace.name}: capture added an event inside the set window"
            )
        }
        assertEquals(2, extras, "handling bursts capture admits across the four recordings")
    }
}
