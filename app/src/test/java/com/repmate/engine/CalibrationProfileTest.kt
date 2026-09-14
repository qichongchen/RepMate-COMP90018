package com.repmate.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CalibrationProfile]: the margins, the safety bounds, the consistency gate, and the
 * fallback to [SquatRepDetector]'s tuned defaults when a calibration is rejected.
 *
 * ## Why every expected value is a literal
 * Until this file existed nothing covered calibration, so `MIN_DURATION_MARGIN` could be changed
 * and the whole suite stayed green. A test that recomputed its expectation as
 * `shortest * MIN_DURATION_MARGIN` would share that blind spot, because it follows the constant
 * wherever it goes. So each expectation here is the number the current constants produce,
 * written out: change a margin or a bound and the test that pins it goes red, and whoever
 * changed it has to update the number deliberately.
 *
 * ## Why the samples are constructed
 * Calibration consumes [RepEvent]s, not frames, so five literal reps exercise exactly the
 * arithmetic under test with no signal processing in between. The fallback tests are the one
 * place a recording is needed, because "behaves like the defaults" can only be observed by
 * running a detector.
 */
class CalibrationProfileTest {

    /**
     * Reps laid end to end, each starting [gapMs] after the previous one ended.
     *
     * @param durations each rep's duration in ms, in order.
     * @param amplitudes each rep's peak-to-peak amplitude, in order.
     * @param gapMs quiet time between consecutive reps; negative makes them overlap.
     */
    private fun samples(
        durations: List<Long>,
        amplitudes: List<Float> = List(durations.size) { 4.0f },
        gapMs: Long = 1000L
    ): List<RepEvent> {
        require(durations.size == amplitudes.size) { "one amplitude per duration" }
        var start = 0L
        return durations.indices.map { i ->
            RepEvent(
                index = i, startMs = start, endMs = start + durations[i], amplitude = amplitudes[i]
            ).also { start = it.endMs + gapMs }
        }
    }

    private fun accepted(samples: List<RepEvent>): CalibrationProfile {
        val outcome = CalibrationProfile.evaluate(samples)
        val result = assertIs<CalibrationOutcome.Accepted>(outcome, "expected acceptance: $outcome")
        return result.profile
    }

    private fun rejected(samples: List<RepEvent>): CalibrationOutcome.Rejected {
        val outcome = CalibrationProfile.evaluate(samples)
        return assertIs<CalibrationOutcome.Rejected>(outcome, "expected rejection: $outcome")
    }

    /** The derivation note for one value, e.g. `minRepDurationMs = 280 ms CLAMPED from ...`. */
    private fun CalibrationProfile.note(name: String): String =
        notes.single { it.startsWith("$name = ") }

    private val squatTraces: List<LabelledTrace> by lazy {
        TraceLibrary.loadAll().filter { it.expectation.exercise == ExerciseType.SQUAT }
    }

    // --- margins -----------------------------------------------------------------------------

    @Test
    fun `every margin is applied to the calibration set`() {
        // Shortest 800, longest 1000, softest 4.0, fastest gap 1000. Every derived value lands
        // inside its bounds, so nothing asserted here is a clamp.
        val profile = accepted(
            samples(
                durations = listOf(800L, 900L, 1000L, 900L, 850L),
                amplitudes = listOf(4.0f, 5.0f, 6.0f, 5.0f, 4.5f),
                gapMs = 1000L
            )
        )

        assertFalse(profile.wasClamped, "no value should hit a bound here: ${profile.notes}")
        assertEquals(600L, profile.minRepDurationMs, "800 ms shortest x 0.75")
        assertEquals(2.6f, profile.minAmplitude, 0.001f, "4.0 softest x 0.65")
        assertEquals(2200L, profile.maxRepDurationMs, "1000 ms longest x 2.2")
        assertEquals(750L, profile.cooldownMs, "1000 ms fastest gap x 0.75")

        // The raw measurements each derived value is traceable to.
        assertEquals(5, profile.sampleCount)
        assertEquals(4.0f, profile.softestSampleAmplitude)
        assertEquals(800L, profile.shortestSampleMs)
        assertEquals(1000L, profile.longestSampleMs)
        assertEquals(1000L, profile.fastestSampleGapMs)
    }

    // --- the duration floor and ceiling ------------------------------------------------------

    @Test
    fun `a derived duration floor below 280 ms is clamped up to 280`() {
        // The case the floor was lowered for: genuine live reps of 300 and 320 ms. A calibration
        // that fast derives 300 x 0.75 = 225 ms, which the floor raises to 280 — still low enough
        // for both measured reps to clear. At the old 400 ms floor neither would.
        val profile = accepted(samples(durations = listOf(300L, 340L, 360L, 320L, 350L)))

        assertEquals(280L, profile.minRepDurationMs)
        val note = profile.note("minRepDurationMs")
        assertTrue(note.contains("CLAMPED from 225 ms"), note)
        assertTrue(
            300L >= profile.minRepDurationMs && 320L >= profile.minRepDurationMs,
            "the measured 300 and 320 ms reps must clear the clamped floor"
        )
    }

    @Test
    fun `a derived duration floor landing exactly on 280 ms is not reported as clamped`() {
        // 374 x 0.75 = 280.5, truncated to 280: on the bound, not below it, so the note must not
        // claim a clamp. A floor above 280 would clamp this one and fail the value check too.
        val profile = accepted(samples(durations = listOf(374L, 400L, 420L, 390L, 410L)))

        assertEquals(280L, profile.minRepDurationMs)
        val note = profile.note("minRepDurationMs")
        assertFalse(note.contains("CLAMPED"), note)
    }

    @Test
    fun `a derived duration floor above 1200 ms is clamped down to 1200`() {
        // 1700 x 0.75 = 1275. A floor that high would start rejecting ordinary reps.
        val profile = accepted(samples(durations = listOf(1700L, 1800L, 1900L, 1750L, 2000L)))

        assertEquals(1200L, profile.minRepDurationMs)
        val note = profile.note("minRepDurationMs")
        assertTrue(note.contains("CLAMPED from 1275 ms"), note)
    }

    // --- the other safety bounds -------------------------------------------------------------

    @Test
    fun `amplitude, max-duration and cooldown clamp up to their floors`() {
        // Softest 0.4 x 0.65 = 0.26, longest 700 x 2.2 = 1540, fastest gap 200 x 0.75 = 150:
        // all three derive below their floors.
        val profile = accepted(
            samples(
                durations = listOf(600L, 650L, 700L, 620L, 680L),
                amplitudes = listOf(0.4f, 0.45f, 0.5f, 0.42f, 0.48f),
                gapMs = 200L
            )
        )

        assertEquals(0.35f, profile.minAmplitude, 0.001f)
        assertEquals(1600L, profile.maxRepDurationMs)
        assertEquals(200L, profile.cooldownMs)
        assertTrue(profile.wasClamped)
    }

    @Test
    fun `amplitude, max-duration and cooldown clamp down to their ceilings`() {
        // Softest 13 x 0.65 = 8.45, longest 1500 x 2.2 = 3300, fastest gap 3000 x 0.75 = 2250:
        // all three derive above their ceilings.
        val profile = accepted(
            samples(
                durations = listOf(1400L, 1450L, 1500L, 1420L, 1480L),
                amplitudes = listOf(13.0f, 14.0f, 15.0f, 13.5f, 14.5f),
                gapMs = 3000L
            )
        )

        assertEquals(8.0f, profile.minAmplitude, 0.001f)
        assertEquals(3000L, profile.maxRepDurationMs)
        assertEquals(900L, profile.cooldownMs)
        assertTrue(profile.wasClamped)
    }

    // --- the consistency gate ----------------------------------------------------------------

    @Test
    fun `an amplitude spread beyond 3x is rejected, and exactly 3x is accepted`() {
        val durations = List(5) { 700L }

        val tooWide = rejected(
            samples(durations, amplitudes = listOf(1.0f, 3.1f, 2.0f, 2.0f, 2.0f))
        )
        assertEquals(
            CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT, tooWide.reason, tooWide.detail
        )

        // The limit is inclusive, so the boundary itself must still derive a profile.
        accepted(samples(durations, amplitudes = listOf(1.0f, 3.0f, 2.0f, 2.0f, 2.0f)))
    }

    @Test
    fun `a duration spread beyond 2x is rejected, and exactly 2x is accepted`() {
        val tooWide = rejected(samples(durations = listOf(500L, 1001L, 700L, 700L, 700L)))
        assertEquals(
            CalibrationOutcome.Reason.DURATION_INCONSISTENT, tooWide.reason, tooWide.detail
        )

        accepted(samples(durations = listOf(500L, 1000L, 700L, 700L, 700L)))
    }

    @Test
    fun `fewer than five reps is rejected`() {
        val outcome = rejected(samples(durations = listOf(700L, 700L, 700L, 700L)))
        assertEquals(CalibrationOutcome.Reason.TOO_FEW_SAMPLES, outcome.reason, outcome.detail)
    }

    @Test
    fun `overlapping reps are rejected rather than derived from`() {
        // A capture bug, not a user error: each rep starts before the previous one has ended.
        val outcome = rejected(samples(durations = List(5) { 700L }, gapMs = -100L))
        assertEquals(CalibrationOutcome.Reason.UNUSABLE_TIMING, outcome.reason, outcome.detail)
    }

    // --- falling back to the defaults --------------------------------------------------------

    @Test
    fun `a rejected calibration falls back to the tuned defaults`() {
        // Rejected on a 5x amplitude spread. fromSamples turns the rejection into null, and the
        // calibration constructor turns null into the tuned defaults.
        val profile = CalibrationProfile.fromSamples(
            samples(durations = List(5) { 700L }, amplitudes = listOf(1.0f, 5.0f, 2.0f, 2.0f, 2.0f))
        )
        assertNull(profile, "a rejected calibration must not produce a profile")

        // Observed through behaviour, because the detector's thresholds are private: on every
        // recorded squat trace the fallback detector must report exactly the default's events.
        assertTrue(squatTraces.isNotEmpty(), "no squat traces were found")
        for (trace in squatTraces) {
            assertEquals(
                SquatRepDetector().processAll(trace.frames),
                SquatRepDetector(profile).processAll(trace.frames),
                "${trace.name}: a rejected calibration changed what the detector reports"
            )
        }
    }

    @Test
    fun `an accepted calibration does change the detector, so the fallback test can tell`() {
        // Guards the test above against passing vacuously: if calibrated and default detectors
        // always agreed on the recordings, falling back would be unobservable. On squat_10_hit
        // they differ — the profile derived from its first five reps drops a phone-handling
        // burst the defaults count.
        val hit = squatTraces.single { it.name == "squat_10_hit" }
        val calibration = SquatRepDetector().processAll(hit.setWindow())
            .take(CalibrationProfile.REQUIRED_SAMPLES)
        val profile = assertNotNull(CalibrationProfile.fromSamples(calibration))

        assertNotEquals(
            SquatRepDetector().processAll(hit.frames),
            SquatRepDetector(profile).processAll(hit.frames),
            "a calibrated detector should report differently from the defaults on squat_10_hit"
        )
    }
}
