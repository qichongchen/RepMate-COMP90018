package com.repmate.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [JumpingJackRepDetector] and the [BurstDetector] it pairs on top of.
 *
 * ## Why these are synthetic when the replay traces exist
 * The two paced recordings *are* in the library, and [RepDetectorTest] replays them: it is
 * what pins 10 reps on each, and it is the evidence the thresholds actually rest on.
 *
 * This file does the other job. It pins the *model* rather than the *counts*: that two
 * impacts pair into one rep, that rebounds are discarded without shifting the rep window,
 * that a stream starting mid-rep resynchronises instead of pairing across two repetitions,
 * that an unpaired jump-out is held rather than counted. Those are properties of the
 * algorithm, and several of them cannot be provoked by the recordings at all — no fixture
 * happens to start mid-rep, and none drops a landing.
 *
 * Constructed signal is the only way to exercise a case the data does not contain. The
 * split is deliberate: **real recordings for whether the numbers are right, synthetic
 * signal for whether the logic is right.**
 *
 * The synthetic signal below is shaped to the magnitudes the recordings actually show —
 * soft impacts near 9.2-9.7 against a measured 9.05-11.72, hard near 18.4 against a
 * measured 12.03-23.50, rebounds near 1.9 against a measured ceiling of 1.87 — so these
 * tests make the same decisions, at the same scale, as the replay ones.
 */
class JumpingJackRepDetectorTest {

    // --- synthetic signal ---------------------------------------------------------------

    /**
     * Builds a magnitude trace out of flat segments at 100 Hz.
     *
     * Each impact is a raised pulse followed by an **unweighting dip** to 9.0. The dip is not
     * decoration: the Schmitt trigger only releases under 9.7, and a phone at rest reads ~9.81,
     * so without the dip the first burst would open and never close. A real jump has the same
     * shape — the body is briefly unloaded around the impact.
     */
    private class TraceBuilder {
        val frames = mutableListOf<MotionFrame>()
        var t = 0L
            private set

        fun hold(durationMs: Long, magnitude: Float) = apply {
            val end = t + durationMs
            while (t < end) {
                frames += MotionFrame(t, 0f, magnitude, 0f, 0f, 0f, 0f)
                t += 10L
            }
        }

        /** One impact: a [pulseMs] pulse at [peak], then the 250 ms unweighting dip. */
        fun impact(peak: Float, pulseMs: Long) = hold(pulseMs, peak).hold(250L, 9.0f)

        /** Phone still, reading resting gravity. */
        fun still(durationMs: Long) = hold(durationMs, 9.81f)

        /**
         * An artificially sharp strike: one sample at 13 g, then free fall.
         *
         * Contrived on purpose. Through the 250 ms filter this is the only shape that
         * produces a burst which **clears** the amplitude floor (4.32 against 4.1) while
         * lasting under the duration floor (120 ms against 130 ms), so it is the one signal
         * that isolates the duration guard from the amplitude guard. 120 ms is also exactly
         * the longest rebound the reference recordings report.
         */
        fun sharpSpike() = hold(10L, 130f).hold(400L, 0f)

        /** Jump out then land: the two impacts of one jumping jack. */
        fun jumpingJack() = impact(JUMP_OUT_PEAK, JUMP_OUT_PULSE_MS).impact(LANDING_PEAK, LANDING_PULSE_MS)
    }

    private fun pacedSet(reps: Int): List<MotionFrame> {
        val b = TraceBuilder().still(600)
        repeat(reps) { b.jumpingJack() }
        return b.still(600).frames
    }

    // --- the model ----------------------------------------------------------------------

    @Test
    fun `a paced ten-rep set is counted as ten reps`() {
        val detected = JumpingJackRepDetector().processAll(pacedSet(reps = 10))

        assertEquals(
            10, detected.size,
            "expected 10 reps from a 10-rep paced set, got ${detected.size}: " +
                detected.joinToString { "${it.startMs}-${it.endMs} ms" }
        )
        // Each rep must span its own pair: open at the jump-out, close at the landing.
        detected.forEachIndexed { i, rep ->
            assertTrue(rep.endMs > rep.startMs, "rep $i has a non-positive span")
            assertEquals(i, rep.index, "reps must be indexed in completion order")
        }
    }

    @Test
    fun `the same set yields two bursts per rep, which is why pairing is needed`() {
        // The whole reason this detector exists rather than reusing SquatRepDetector. The
        // burst layer sees 20 events in a 10-rep set — the 2.0 bursts/rep the two paced
        // recordings both show. A burst-per-rep counter would report 20.
        val frames = pacedSet(reps = 10)

        val bursts = BurstDetector().processAll(frames)
        val reps = JumpingJackRepDetector().processAll(frames)

        assertEquals(20, bursts.size, "expected 2 bursts per rep from the burst layer")
        assertEquals(10, reps.size, "expected the pairing layer to halve that")
        assertEquals(
            JumpingJackRepDetector.PACED_BURSTS_PER_REP, bursts.size / reps.size,
            "the paced bursts-per-rep ratio the pairing model rests on"
        )
    }

    @Test
    fun `the landing is harder than the jump-out in every pair`() {
        // The ordering the pairing rule depends on, asserted on the signal rather than
        // assumed. If a fixture ever violates this, the relative test is the wrong model.
        val bursts = BurstDetector().processAll(pacedSet(reps = 10))

        bursts.chunked(2).forEachIndexed { i, pair ->
            val (jumpOut, landing) = pair
            assertTrue(
                landing.amplitude > jumpOut.amplitude,
                "rep $i: landing (${landing.amplitude}) must exceed jump-out (${jumpOut.amplitude})"
            )
        }
    }

    // --- rebound rejection ---------------------------------------------------------------

    @Test
    fun `a rebound between the two impacts is discarded and does not shift the rep window`() {
        // The failure this guard exists for is subtler than a miscount. Without the amplitude
        // cutoff the rebound becomes the held "jump-out" and the rep still counts as one —
        // but it starts at the rebound instead of at the real take-off, so every downstream
        // tempo measurement is wrong. Hence asserting the window, not just the count.
        val frames = TraceBuilder()
            .still(600)
            .impact(JUMP_OUT_PEAK, JUMP_OUT_PULSE_MS)   // real take-off, opens at 600 ms
            .impact(15f, 100L)                          // rebound: ~1.9 peak-to-peak
            .impact(LANDING_PEAK, LANDING_PULSE_MS)     // real landing
            .still(600)
            .frames

        val rebound = BurstDetector().processAll(frames)[1]
        assertTrue(
            rebound.amplitude < JumpingJackRepDetector.DEFAULT_MIN_BURST_AMPLITUDE,
            "the fixture's middle burst should be rebound-sized, was ${rebound.amplitude}"
        )

        val detected = JumpingJackRepDetector().processAll(frames)

        assertEquals(1, detected.size, "the rebound must not add or remove a rep")
        assertEquals(
            600L, detected.single().startMs,
            "the rep must open at the real take-off, not at the rebound (${rebound.startMs} ms)"
        )
    }

    @Test
    fun `the duration guard rejects impacts that are too brief`() {
        // Pins that minBurstDurationMs is wired in at all: raise it above the jump-out's
        // ~340 ms and there is nothing left to pair with the landing.
        val frames = TraceBuilder().still(600).jumpingJack().still(600).frames

        assertEquals(1, JumpingJackRepDetector().processAll(frames).size)
        assertEquals(
            0, JumpingJackRepDetector(minBurstDurationMs = 400L).processAll(frames).size,
            "a duration floor above the jump-out's length should leave nothing to pair"
        )
    }

    @Test
    fun `the default duration floor rejects a burst the amplitude floor lets through`() {
        // Pins the *default* 130 ms, which the test above does not: it passes an explicit
        // override, so it would still pass if the default were 0.
        //
        // This needs a burst that clears 4.1 but is shorter than 130 ms, and through a
        // 250 ms moving average that is a narrow target — see TraceBuilder.sharpSpike.
        val frames = TraceBuilder()
            .still(600)
            .impact(JUMP_OUT_PEAK, JUMP_OUT_PULSE_MS)   // real take-off, opens at 600 ms
            .sharpSpike()                               // 4.32 amplitude, 120 ms
            .impact(LANDING_PEAK, LANDING_PULSE_MS)     // real landing
            .still(600)
            .frames

        val spike = BurstDetector().processAll(frames)[1]
        assertTrue(
            spike.amplitude >= JumpingJackRepDetector.DEFAULT_MIN_BURST_AMPLITUDE,
            "the fixture's middle burst must clear the amplitude floor, was ${spike.amplitude}"
        )
        assertTrue(
            spike.durationMs < JumpingJackRepDetector.DEFAULT_MIN_BURST_DURATION_MS,
            "...and must fall under the duration floor, was ${spike.durationMs} ms"
        )

        assertEquals(
            600L, JumpingJackRepDetector().processAll(frames).single().startMs,
            "the duration guard must discard the spike so the rep still opens at the take-off"
        )
        // And the counter-case, showing the guard is what makes the difference.
        assertEquals(
            spike.startMs,
            JumpingJackRepDetector(minBurstDurationMs = 0L).processAll(frames).single().startMs,
            "with the duration floor removed the spike should capture the rep window"
        )
    }

    // --- pairing edge cases ---------------------------------------------------------------

    @Test
    fun `a recording that starts mid-rep resynchronises instead of pairing across reps`() {
        // A landing whose take-off was never recorded — the set began before the phone
        // started logging. Pairing it with the *next* rep's take-off would merge two
        // repetitions into one and place the rep window across the gap between them.
        val frames = TraceBuilder()
            .still(600)
            .impact(LANDING_PEAK, LANDING_PULSE_MS)  // orphan landing
            .jumpingJack()
            .jumpingJack()
            .still(600)
            .frames

        val detected = JumpingJackRepDetector().processAll(frames)

        assertEquals(2, detected.size, "the orphan landing must be dropped, not paired")
        assertTrue(
            detected.first().startMs > 600L,
            "the first rep must start after the orphan landing, not at it"
        )
    }

    @Test
    fun `two impacts of the same force are not a rep`() {
        // Soft-then-hard is the whole model. Two equal impacts are not a jumping jack, and
        // pairing them would count arbitrary repeated movement as reps.
        val frames = TraceBuilder().still(600).impact(32f, 150L).impact(32f, 150L).still(600).frames

        assertEquals(0, JumpingJackRepDetector().processAll(frames).size)
    }

    @Test
    fun `an unpaired jump-out is held rather than counted`() {
        val frames = TraceBuilder().still(600).impact(JUMP_OUT_PEAK, JUMP_OUT_PULSE_MS).still(600).frames
        val detector = JumpingJackRepDetector()

        assertEquals(0, detector.processAll(frames).size, "a take-off alone is not a rep")
        assertTrue(detector.awaitingLanding, "the jump-out should still be held")
    }

    // --- housekeeping ---------------------------------------------------------------------

    @Test
    fun `detection is deterministic across detector instances`() {
        val frames = pacedSet(reps = 10)

        assertEquals(
            JumpingJackRepDetector().processAll(frames),
            JumpingJackRepDetector().processAll(frames),
            "two instances must agree field by field"
        )
    }

    @Test
    fun `reset returns the detector to its initial state`() {
        val frames = pacedSet(reps = 10)
        val detector = JumpingJackRepDetector()
        detector.processAll(frames)
        assertEquals(10, detector.repCount)

        detector.reset()

        assertEquals(0, detector.repCount, "reset must clear counted reps")
        assertFalse(detector.awaitingLanding, "reset must drop any held jump-out")
        assertEquals(
            10, detector.processAll(frames).size,
            "a reset detector must behave like a fresh one"
        )
    }

    // --- wiring ----------------------------------------------------------------------------

    @Test
    fun `a trace is replayed through the detector its expectation names`() {
        // Guards the seam the .expect format depends on. Before LabelledTrace.detect existed
        // every test hardcoded SquatRepDetector, so the `exercise` key was parsed, validated
        // — and ignored. This pins that the key actually selects the detector, by running one
        // set of frames under both labels and requiring the answers to differ.
        val frames = pacedSet(reps = 10)
        fun labelled(exercise: ExerciseType) = LabelledTrace(
            name = "synthetic_$exercise",
            frames = frames,
            expectation = TraceExpectation(
                exercise = exercise,
                reps = 10,
                setStartMs = 0,
                setEndMs = frames.last().tMillis
            )
        )

        assertEquals(
            10, labelled(ExerciseType.JUMPING_JACK).detect(frames).size,
            "a JUMPING_JACK trace must run through the pairing detector"
        )
        assertEquals(
            0, labelled(ExerciseType.SQUAT).detect(frames).size,
            "the same frames under a SQUAT label must run through the squat detector, whose " +
                "one-burst-per-rep model finds nothing here — if this matches the line above, " +
                "the exercise key is being ignored again"
        )
    }

    private companion object {
        /** Produces soft impacts measuring ~9.2-9.7 peak-to-peak, inside the reported 9.0-11.7. */
        const val JUMP_OUT_PEAK = 32f
        const val JUMP_OUT_PULSE_MS = 100L

        /** Produces hard impacts measuring ~18.4, inside the reported 12.3-23.5. */
        const val LANDING_PEAK = 32f
        const val LANDING_PULSE_MS = 200L
    }
}
