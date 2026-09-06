package com.repmate.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay tests for [SquatRepDetector], [parseSensorLoggerCsv] and [parseTraceExpectation].
 *
 * These are **replay tests**: recordings are made once on a real phone and every assertion
 * re-runs the detector over them. That is what makes the thresholds in [SquatRepDetector]
 * defensible — retune them and this suite says immediately what it cost.
 *
 * ## Adding a recording
 * Drop `my_trace.csv` and `my_trace.expect` into `traces/`. Nothing here changes: every
 * test below iterates [TraceLibrary.loadAll], so a new recording is picked up
 * automatically and its ground truth comes from its own `.expect` file. Thresholds tuned
 * against one person at one pace are a guess until other recordings agree, so adding them
 * has to be cheap.
 *
 * ## Why the loops aggregate instead of failing fast
 * Each test collects every trace's failure and reports them together. With one recording
 * that is the same thing; with five, one bad trace failing fast would hide the other four,
 * and "which recordings disagree" is exactly the question when retuning.
 *
 * Pure JVM: no `android.*` anywhere, so these need no emulator.
 */
class RepDetectorTest {

    private val traces get() = loadedTraces

    /**
     * Runs [check] against every trace and fails once, naming every trace that disagreed.
     *
     * @param what label for the property under test, used in the failure message.
     * @param check returns null when the trace passes, or a description of the problem.
     */
    private fun eachTrace(what: String, check: (LabelledTrace) -> String?) {
        val failures = traces.mapNotNull { trace -> check(trace)?.let { "  ${trace.name}: $it" } }
        assertTrue(
            failures.isEmpty(),
            "$what failed for ${failures.size} of ${traces.size} trace(s):\n" +
                failures.joinToString("\n")
        )
    }

    @Test
    fun `trace library finds recordings and every one has ground truth`() {
        // Guards the suite against quietly passing on nothing: if traces/ went missing or
        // a recording lost its .expect file, every other test here would iterate an empty
        // list and report success. TraceLibrary.loadAll throws on a missing companion
        // file, so simply calling it is most of this assertion.
        assertTrue(traces.isNotEmpty(), "traces/ contained no recordings")
    }

    @Test
    fun `each trace detects its ground-truth rep count inside the set window`() {
        // The honest headline number: within the window where the human actually
        // performed the set, does the count match what they did? Phone handling before
        // and after the set is excluded by the window, so this is not flattered by it.
        eachTrace("ground-truth rep count") { trace ->
            val detected = SquatRepDetector().processAll(trace.setWindow()).size
            if (trace.expectation.acceptsRepCount(detected)) {
                null
            } else {
                "expected ${trace.expectation.acceptedRangeDescription()} reps in " +
                    "${trace.expectation.setStartMs}-${trace.expectation.setEndMs} ms, detected $detected"
            }
        }
    }

    @Test
    fun `each trace reproduces its recorded whole-recording event count`() {
        // The other half of the story: what the detector sees across the raw file,
        // handling artifacts included. For squat_10_pocket that is 12, not 10 — the 10
        // squats plus a phone-into-pocket burst and a phone-out-of-pocket burst. Pinning
        // it means a threshold change cannot quietly alter what the raw signal yields.
        eachTrace("whole-recording event count") { trace ->
            val expected = trace.expectation.fullTraceEvents ?: return@eachTrace null
            val detected = SquatRepDetector().processAll(trace.frames).size
            if (detected == expected) {
                null
            } else {
                "expected $expected events over the whole recording, detected $detected"
            }
        }
    }

    @Test
    fun `declared quiet windows produce no reps`() {
        // False-positive check. A phone lying still reads roughly gravity (~9.81 m/s^2),
        // which must never cross the 10.15 high threshold.
        eachTrace("quiet window") { trace ->
            val quiet = trace.quietWindow() ?: return@eachTrace null
            if (quiet.isEmpty()) return@eachTrace "declared a quiet window containing no frames"
            val detected = SquatRepDetector().processAll(quiet).size
            if (detected == 0) null else "a still phone produced $detected rep(s)"
        }
    }

    @Test
    fun `detection is deterministic across detector instances`() {
        // Determinism is a hard requirement: the same trace must always yield the same
        // reps, or replay-tuned thresholds mean nothing. RepEvent is a data class, so this
        // compares index, start, end and amplitude field by field.
        eachTrace("determinism") { trace ->
            val first = SquatRepDetector().processAll(trace.frames)
            val second = SquatRepDetector().processAll(trace.frames)
            if (first == second) null else "two instances disagreed: ${first.size} vs ${second.size} reps"
        }
    }

    @Test
    fun `synthetic ten-rep trace is detected within one rep`() {
        // The non-default parameters are required, and that is a property of the fixture,
        // not a weakness in the detector.
        //
        // syntheticSquatTrace lays down one clean sine cycle every 100 samples at 50 Hz,
        // i.e. a rep starts exactly every 2000 ms with no rest between them. The
        // production cooldownMs of 2000 ms says "a new rep may not begin within 2 s of the
        // previous one ending", so on this trace every second rep lands inside the
        // cooldown and is discarded — the default detector reports 5, not 10.
        //
        // That cooldown is right for humans (nobody squats back-to-back at a metronomic
        // 2 s with zero pause) and wrong for this idealised generator. So the cooldown is
        // switched off here, and minRepDurationMs is relaxed to 300 ms because the
        // synthetic burst is shorter than a real squat's.
        //
        // 9 or 10 rather than exactly 10: the moving-average filter needs its 25-sample
        // warm-up, so the first cycle can be clipped below the threshold. Losing at most
        // one rep at the very start is expected behaviour for a causal filter.
        val synthetic = syntheticSquatTrace(reps = 10)

        val detected = SquatRepDetector(cooldownMs = 0L, minRepDurationMs = 300L)
            .processAll(synthetic)

        assertTrue(
            detected.size in 9..10,
            "expected 9 or 10 reps from a synthetic 10-rep trace, got ${detected.size}"
        )
    }

    @Test
    fun `csv columns are resolved by header name not by position`() {
        // Sensor Logger writes its axes in the order z,y,x — reversed. A positional parser
        // reading "cell 2 is x" would silently swap the axes and every magnitude computed
        // downstream would still look plausible, which is what makes this bug nasty. This
        // literal CSV pins the mapping: if the parser ever regresses to positional
        // reading, ax and az swap and the assertions below fail.
        val csv = listOf(
            "time,seconds_elapsed,z,y,x",
            "1788594189329554400,1.5,3.0,2.0,1.0",
            "1788594189529554400,2.5,6.0,5.0,4.0"
        )

        val frames = parseSensorLoggerCsv(csv)

        assertEquals(2, frames.size)

        // seconds_elapsed 1.5 -> 1500 ms; x/y/z taken from their *named* columns.
        assertEquals(1500L, frames[0].tMillis)
        assertEquals(1.0f, frames[0].ax, "ax must come from the 'x' column (last), not position")
        assertEquals(2.0f, frames[0].ay)
        assertEquals(3.0f, frames[0].az, "az must come from the 'z' column (third), not position")

        assertEquals(2500L, frames[1].tMillis)
        assertEquals(4.0f, frames[1].ax)
        assertEquals(5.0f, frames[1].ay)
        assertEquals(6.0f, frames[1].az)

        // A Total Acceleration export carries no gyroscope — degrade to zeros, never fail.
        assertEquals(0f, frames[0].gx)
        assertEquals(0f, frames[0].gy)
        assertEquals(0f, frames[0].gz)
    }

    @Test
    fun `expectation parser rejects an unknown key`() {
        // A typo'd key such as 'fullTraceEvent' would otherwise be ignored, switching off
        // an assertion while the suite stayed green — the worst way for a fixture to fail.
        // So the parser rejects anything it does not recognise, and this pins that.
        val lines = listOf(
            "exercise = SQUAT",
            "reps = 10",
            "setStartMs = 0",
            "setEndMs = 1000",
            "fullTraceEvent = 12" // note the missing 's'
        )

        val message = messageFromFailure { parseTraceExpectation(lines) }

        assertTrue(
            message.contains("unknown key"),
            "expected an 'unknown key' complaint, got: $message"
        )
    }

    /** Runs [block] and returns the message of the exception it throws; fails if it succeeds. */
    private fun messageFromFailure(block: () -> Unit): String {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(thrown != null, "expected the call to fail, but it succeeded")
        return thrown.message.orEmpty()
    }

    companion object {
        /**
         * Loaded once for the whole class rather than per test.
         *
         * JUnit constructs a fresh test instance for every method, so a per-instance load
         * would re-parse every CSV eight times — 6500 rows each today, and more with every
         * recording added.
         */
        private val loadedTraces: List<LabelledTrace> by lazy { TraceLibrary.loadAll() }
    }
}
