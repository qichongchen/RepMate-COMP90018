package com.repmate.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SquatRepDetector.onRejectedWindow]: the report that lets a missed rep be traced to
 * the guard that dropped it. Each report is checked against a specific non-rep in a specific
 * recording, for the guard that is *known* to be the one rejecting it (see the notes on
 * [SquatRepDetector.forCalibrationCapture]).
 */
class RejectedWindowTest {

    private val squatTraces: List<LabelledTrace> by lazy {
        TraceLibrary.loadAll().filter { it.expectation.exercise == ExerciseType.SQUAT }
    }

    private fun trace(name: String) = squatTraces.single { it.name == name }

    private fun run(detector: SquatRepDetector, frames: List<MotionFrame>): Pair<List<RepEvent>, List<RejectedWindow>> {
        val rejected = mutableListOf<RejectedWindow>()
        detector.onRejectedWindow = { rejected += it }
        return detector.processAll(frames) to rejected
    }

    private fun List<RejectedWindow>.near(startMs: Long) =
        singleOrNull { abs(it.startMs - startMs) <= 50 }

    @Test
    fun `listening changes nothing about what is counted`() {
        val detectors = listOf<() -> SquatRepDetector>(
            { SquatRepDetector() },
            { SquatRepDetector.forCalibrationCapture() },
            { SquatRepDetector(minRepDurationMs = 2000L, minAmplitude = 100f) }, // rejects almost everything
        )
        for (trace in squatTraces) {
            for (make in detectors) {
                val silent = make().processAll(trace.frames)
                val (heard, _) = run(make(), trace.frames)
                assertEquals(silent, heard, "${trace.name}: attaching a listener changed the reps counted")
            }
        }
    }

    @Test
    fun `a counted rep is never also reported as rejected`() {
        for (trace in squatTraces) {
            val (reps, rejected) = run(SquatRepDetector.forCalibrationCapture(), trace.frames)
            val repStarts = reps.map { it.startMs }.toSet()
            assertEquals(emptyList(), rejected.filter { it.startMs in repStarts }, trace.name)
        }
    }

    @Test
    fun `Hit's wobble is reported as too small and nothing else`() {
        val (_, rejected) = run(SquatRepDetector.forCalibrationCapture(), trace("squat_10_hit").frames)
        val wobble = assertNotNullWindow(rejected.near(63477L), "Hit's wobble at ~63.5 s")

        assertEquals(listOf(RejectionGuard.TOO_SMALL), wobble.failures.map { it.guard })
        val failure = wobble.failures.single()
        assertTrue(failure.measured in 0.80..0.90, "measured ${failure.measured}")
        assertEquals(SquatRepDetector.DEFAULT_MIN_AMPLITUDE.toDouble(), failure.limit, 1e-6)
    }

    @Test
    fun `Lisa's rebounds are reported as too soon after the last rep and nothing else`() {
        val (_, rejected) = run(SquatRepDetector.forCalibrationCapture(), trace("squat_10_lisa").frames)
        for (startMs in listOf(8799L, 22003L)) {
            val rebound = assertNotNullWindow(rejected.near(startMs), "Lisa's rebound at $startMs ms")
            assertEquals(listOf(RejectionGuard.TOO_SOON_AFTER_LAST_REP), rebound.failures.map { it.guard })
            val failure = rebound.failures.single()
            assertTrue(failure.measured < failure.limit, "gap ${failure.measured} vs cooldown ${failure.limit}")
            assertEquals(SquatRepDetector.DEFAULT_COOLDOWN_MS.toDouble(), failure.limit, 1e-6)
        }
    }

    @Test
    fun `Hit's parked-phone window is reported as abandoned, at the limit`() {
        val (_, rejected) = run(SquatRepDetector(), trace("squat_10_hit").frames)
        val stuck = assertNotNullWindow(rejected.near(100900L), "the window opened at ~100.9 s")

        assertEquals(listOf(RejectionGuard.ABANDONED_TOO_LONG), stuck.failures.map { it.guard })
        val failure = stuck.failures.single()
        assertEquals(SquatRepDetector.DEFAULT_MAX_REP_DURATION_MS.toDouble(), failure.limit, 1e-6)
        assertTrue(failure.measured > failure.limit, "abandoned at ${failure.measured} ms")
    }

    @Test
    fun `fast reps the default duration floor drops are reported as too short, with their length`() {
        // The live miss this whole calibration change is about: reps at 0.6x time run 417-514 ms
        // against a 550 ms floor, and the report has to say so rather than leave a gap.
        val fast = trace("squat_10_fast").setWindow().map { it.copy(tMillis = (it.tMillis * 0.6).toLong()) }
        val (reps, rejected) = run(SquatRepDetector(), fast)

        assertEquals(0, reps.size)

        // Ten windows are the dropped reps, each failing on duration alone.
        val dropped = rejected.filter { it.amplitude > 2f }
        assertEquals(10, dropped.size, "each of the ten dropped reps should be reported once: ${rejected.map { it.describe() }}")
        for (window in dropped) {
            assertEquals(listOf(RejectionGuard.TOO_SHORT), window.failures.map { it.guard }, window.describe())
            assertTrue(window.failures.single().measured in 400.0..550.0, window.describe())
        }

        // The eleventh is a 48 ms, 0.61-amplitude stray at the start of the set: not a rep, and
        // reported as failing both guards rather than just the first one checked.
        val stray = (rejected - dropped.toSet()).single()
        assertEquals(
            listOf(RejectionGuard.TOO_SHORT, RejectionGuard.TOO_SMALL),
            stray.failures.map { it.guard },
            stray.describe()
        )
    }

    @Test
    fun `a window that fails several guards reports all of them`() {
        // Floors set high enough that every real rep is both too brief and too weak.
        val (_, rejected) = run(
            SquatRepDetector(minRepDurationMs = 2000L, minAmplitude = 100f),
            trace("squat_10_pocket").setWindow()
        )
        val both = rejected.filter {
            val guards = it.failures.map { f -> f.guard }
            RejectionGuard.TOO_SHORT in guards && RejectionGuard.TOO_SMALL in guards
        }
        assertTrue(both.isNotEmpty(), "no window reported both guards: ${rejected.map { it.describe() }}")
    }

    @Test
    fun `describe names the guard and shows the number against the limit`() {
        val window = RejectedWindow(
            startMs = 1000L, endMs = 1240L, amplitude = 1.62f,
            failures = listOf(GuardFailure(RejectionGuard.TOO_SHORT, 240.0, 280.0))
        )
        assertEquals("duration 240 ms, amplitude 1.62 | TOO_SHORT (240.00 < 280.00)", window.describe())
    }

    private fun assertNotNullWindow(window: RejectedWindow?, what: String): RejectedWindow =
        window ?: error("$what was not reported as a rejected window")
}
