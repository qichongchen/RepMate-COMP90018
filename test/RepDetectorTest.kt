package engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * Replay tests for [SquatRepDetector] and [parseSensorLoggerCsv].
 *
 * These are **replay tests**: the recording was made once on a real phone, and every
 * assertion below re-runs the detector over that fixed trace. That is what makes the
 * thresholds in [SquatRepDetector] defensible — if someone retunes them and the count
 * changes, this suite says so immediately.
 *
 * Pure JVM: no `android.*` anywhere, so these run in plain unit tests with no emulator.
 */
class RepDetectorTest {

    /**
     * Candidate paths for the recorded trace, tried in order.
     *
     * Mirrors the lookup in `Main.kt`: the file currently lives at the repo root, but
     * `traces/` is where it belongs long term, and a test runner's working directory is
     * not guaranteed. Checking both keeps the suite green either way.
     */
    private val traceCandidates = listOf(
        "traces/squat_10_pocket.csv",
        "squat_10_pocket.csv"
    )

    /** Loads the recorded trace, failing with a useful message if it cannot be found. */
    private fun realTrace(): List<MotionFrame> {
        val path = traceCandidates.firstOrNull { File(it).isFile }
        assertTrue(
            path != null,
            "Recorded trace not found. Looked in: " +
                traceCandidates.joinToString { File(it).absolutePath }
        )
        return loadSensorLoggerCsv(path)
    }

    @Test
    fun `real recording yields twelve rep events`() {
        val trace = realTrace()

        val detected = SquatRepDetector().processAll(trace)

        // 12, not 10, and that is the correct answer for this recording.
        //
        // The human performed 10 squats, and those are events 2..11 — a tidy run from
        // ~12.7 s to ~53.6 s at a steady ~4-5 s cadence. The two extras are phone
        // handling, one at each end of the recording: event 1 at ~3.5 s is the phone
        // being pushed into the pocket after the recording was started, and event 12 at
        // ~63.2 s is it being pulled back out to stop the recording. Both are genuine
        // movement bursts that clear every guard, so a magnitude-based detector counts
        // them; nothing in the signal marks them as "not a squat".
        //
        // Stripping them is a session-boundary problem (trim before the first rep and
        // after the last), not a detector-threshold problem — so this test pins what the
        // detector actually sees, and does not pretend the guards can tell the
        // difference. The ~9.1 s gap before the last event is the tell: see the
        // rest-between-reps output in Main.kt.
        assertEquals(12, detected.size, "expected 10 squats plus 2 phone-handling bursts")
    }

    @Test
    fun `still period in the middle of the recording produces no reps`() {
        // 54-62 s is the rest after the last squat (which ends at ~53.6 s) and before the
        // phone is retrieved (~63.2 s): the phone is sitting still in a pocket. This is
        // the false-positive guard — a resting phone reads ~9.81 m/s^2 of gravity, which
        // must never climb past the 10.15 highThreshold.
        val quiet = realTrace().filter { it.tMillis in 54_000L..62_000L }
        assertTrue(quiet.isNotEmpty(), "quiet window should contain frames")

        val detected = SquatRepDetector().processAll(quiet)

        assertEquals(0, detected.size, "a still phone must not produce reps")
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
    fun `two detector instances produce identical results for the same trace`() {
        // Determinism is a hard requirement: the same trace must always yield the same
        // reps, or replay-tuned thresholds mean nothing. RepEvent is a data class, so
        // this compares index, start, end and amplitude field by field.
        val trace = realTrace()

        val first = SquatRepDetector().processAll(trace)
        val second = SquatRepDetector().processAll(trace)

        assertEquals(first, second, "detection must be deterministic across instances")
    }

    @Test
    fun `csv columns are resolved by header name not by position`() {
        // Sensor Logger writes its axes in the order z,y,x — reversed. A positional
        // parser reading "cell 2 is x" would silently swap the axes and every magnitude
        // computed downstream would still look plausible, which is what makes this bug
        // nasty. This literal CSV pins the mapping: if the parser ever regresses to
        // positional reading, ax and az swap and the assertions below fail.
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
}
