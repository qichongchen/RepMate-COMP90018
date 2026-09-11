package com.repmate.engine

import kotlin.math.sqrt
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
        // syntheticSquatTrace lays down one clean sine cycle every 100 samples at 50 Hz,
        // i.e. a rep starts exactly every 2000 ms with no rest between them.
        //
        // The two overrides below are **no longer required**, and this comment used to say
        // they were. They date from cooldownMs = 2000, which on a 2 s-cadence fixture put
        // every second rep inside the cooldown and yielded 5 of 10. Since the cooldown was
        // split into rebound suppression (500 ms) and minAmplitude, the defaults produce
        // the same 9 reps as the overrides do, so the overrides now only pin that this
        // fixture does not depend on those two guards. Left in place rather than removed
        // because deleting them is a change to what the test asserts, not a comment fix.
        //
        // 9 or 10 rather than exactly 10, and the missing one is the *last*, not the first.
        // This comment used to blame the filter's warm-up clipping the opening cycle. That
        // is wrong: the detector reports reps 1-9 and the generator's tenth cycle opens a
        // window at ~19140 ms that the trace, which stops at 19980 ms, never gives it a
        // chance to close. Replaying at 20, 100, 250 and 500 ms of smoothing all yield the
        // same 9, which is what rules the warm-up out as the cause.
        //
        // A rep window that is still open when the frames run out is correctly not emitted
        // — the detector cannot know a rep finished if it never saw it finish — so this is
        // a property of a fixture that ends mid-movement, not a defect.
        val synthetic = syntheticSquatTrace(reps = 10)

        val detected = SquatRepDetector(cooldownMs = 0L, minRepDurationMs = 300L)
            .processAll(synthetic)

        assertTrue(
            detected.size in 9..10,
            "expected 9 or 10 reps from a synthetic 10-rep trace, got ${detected.size}"
        )
    }


    @Test
    fun `filter length follows the sample rate rather than a fixed sample count`() {
        // The property the duration-based window exists to provide, pinned directly rather
        // than inferred from a rep count. A steady stream at a known interval is fed in and
        // the filter is asked what it settled on: the answer must be "however many samples
        // 250 ms holds at this rate", not a constant.
        //
        // These are the three rates actually seen — ~100 Hz (Pixel), ~58 Hz (Samsung) and
        // the 50 Hz the live stream runs at. Under the previous fixed 25-sample filter all
        // three rows would read 25 samples, spanning 250, 430 and 500 ms respectively, and
        // this test is what would have caught that.
        val cases = listOf(
            Triple(10L, 25, "~100 Hz"), // 250 ms holds samples at ages 0..240 ms
            Triple(17L, 15, "~58 Hz"),
            Triple(20L, 13, "50 Hz")
        )

        cases.forEach { (intervalMs, expectedSamples, label) ->
            val detector = SquatRepDetector()
            // Two windows' worth, so the filter is well past its warm-up and steady.
            val frames = (0 until 500L / intervalMs * 2).map { i ->
                MotionFrame(i * intervalMs, 0f, 9.81f, 0f, 0f, 0f, 0f)
            }
            detector.processAll(frames)

            assertEquals(
                expectedSamples, detector.smoothingSampleCount,
                "at $label (every $intervalMs ms) a 250 ms window should hold " +
                    "$expectedSamples samples, not a fixed count"
            )
            // The span the filter covers is what has to stay constant, not the count.
            val spanMs = (detector.smoothingSampleCount - 1) * intervalMs
            assertTrue(
                spanMs in (250L - intervalMs) until 250L,
                "at $label the filter spans $spanMs ms, which is not ~250 ms"
            )
            assertEquals(
                1000f / intervalMs, detector.observedSampleRateHz, 0.5f,
                "at $label the reported rate should match the frames actually fed in"
            )
        }
    }

    @Test
    fun `a window that never closes is abandoned and the detector recovers`() {
        // The squat_10_hit failure, reduced to its essentials. A phone parked between
        // lowThreshold (9.7) and highThreshold (10.15) reads ~9.81, so a window opened
        // before it was set down is never closed by the trigger. Without maxRepDurationMs
        // that window ran 36.9 s on the real trace and was reported as one rep.
        //
        // Three stages: a burst that opens a window, a long park at ~9.81 that cannot close
        // it, then a genuine rep. The park must yield nothing, and — the part a close-time
        // check would not give — the rep after it must still be counted, because the
        // detector has to have returned to IDLE rather than sat stuck in DESCENDING.
        fun frames(fromMs: Long, toMs: Long, magnitude: Float): List<MotionFrame> =
            (fromMs until toMs step 20L).map { MotionFrame(it, 0f, magnitude, 0f, 0f, 0f, 0f) }

        val trace =
            frames(0, 400, 11.5f) +          // burst: opens a window
            frames(400, 30_000, 9.81f) +     // parked: above lowThreshold, so never closes
            frames(30_000, 30_400, 9.0f) +   // finally dips under lowThreshold
            frames(30_400, 31_200, 11.5f) +  // a real rep: 800 ms of movement...
            frames(31_200, 31_600, 9.0f)     // ...and its close

        val detected = SquatRepDetector().processAll(trace)

        assertEquals(
            1, detected.size,
            "expected the 29.6 s parked window to be abandoned and only the closing rep to " +
                "count, got ${detected.size}: " + detected.joinToString {
                    "${it.startMs}-${it.endMs} ms"
                }
        )
        // Not an exact timestamp: the 250 ms filter takes ~100 ms to climb past
        // highThreshold after the signal steps up, so the window opens a little after the
        // movement does. What matters is that it opens during the final burst at all.
        assertTrue(
            detected.single().startMs in 30_400L..31_200L,
            "the surviving rep must be the one after the park, but it started at " +
                "${detected.single().startMs} ms — if the detector were still stuck in " +
                "DESCENDING it could not have opened this window at all"
        )

        // The limit is load-bearing, not decorative: drop it under this fixture's own 800 ms
        // rep and that rep is abandoned too, leaving nothing.
        assertTrue(
            SquatRepDetector(maxRepDurationMs = 600L).processAll(trace).isEmpty(),
            "a limit below the fixture's 800 ms rep should abandon it as well"
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

        val frames = parseSensorLoggerCsv(csv, AccelerationUnit.METRES_PER_SECOND_SQUARED)

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
    fun `an iOS trace in g is converted to metres per second squared`() {
        // Sensor Logger's iOS export writes the same header but in multiples of gravity, so a
        // still phone reads ~1.0 rather than ~9.81. Reading it as m/s^2 would scale every
        // sample down by 9.8, no threshold would ever be crossed, and the detector would
        // report zero reps on a good recording without anything looking broken. This pins the
        // conversion that prevents that.
        val csv = listOf(
            "time,seconds_elapsed,z,y,x",
            "1788689813792495600,0.0,-0.9109802,-0.3657989,0.0177307"
        )

        val frames = parseSensorLoggerCsv(csv, AccelerationUnit.G)

        assertEquals(1, frames.size)
        assertEquals(-0.9109802f * STANDARD_GRAVITY, frames[0].az, TOLERANCE)
        assertEquals(-0.3657989f * STANDARD_GRAVITY, frames[0].ay, TOLERANCE)
        assertEquals(0.0177307f * STANDARD_GRAVITY, frames[0].ax, TOLERANCE)

        // The property that actually matters downstream: a phone at rest sits at ~9.81, which
        // is the resting value every threshold in SquatRepDetector is calibrated around.
        val magnitude = sqrt(
            frames[0].ax * frames[0].ax +
                frames[0].ay * frames[0].ay +
                frames[0].az * frames[0].az
        )
        assertTrue(
            magnitude in 9.5f..10.1f,
            "a still iPhone should read ~9.81 m/s^2 after conversion, got $magnitude"
        )
    }

    @Test
    fun `the same readings differ by gravity depending on the declared unit`() {
        // The two units must not be interchangeable by accident: this is the mistake the
        // required parameter exists to prevent, so it is worth one assertion of its own.
        val csv = listOf(
            "time,seconds_elapsed,z,y,x",
            "1788594189329554400,0.0,1.0,0.0,0.0"
        )

        val asMetres = parseSensorLoggerCsv(csv, AccelerationUnit.METRES_PER_SECOND_SQUARED)
        val asG = parseSensorLoggerCsv(csv, AccelerationUnit.G)

        assertEquals(1.0f, asMetres[0].az)
        assertEquals(STANDARD_GRAVITY, asG[0].az, TOLERANCE)
    }

    @Test
    fun `expectation parser rejects unknown units`() {
        // A misspelled unit must fail loudly rather than fall back to the m/s^2 default, which
        // would read an iOS trace at a ninth of its true scale and silently detect nothing.
        val lines = listOf(
            "exercise = SQUAT",
            "reps = 10",
            "setStartMs = 0",
            "setEndMs = 1000",
            "units = gs" // not a spelling we accept
        )

        val message = messageFromFailure { parseTraceExpectation(lines) }

        assertTrue(
            message.contains("unknown units"),
            "expected an 'unknown units' complaint, got: $message"
        )
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
        /** Float comparison slack for unit conversion, well under any threshold's precision. */
        private const val TOLERANCE = 1e-4f

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
