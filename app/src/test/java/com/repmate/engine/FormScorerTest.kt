package com.repmate.engine

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class FormScorerTest {

    private val scorer = FormScorer()

    private val profile = CalibrationProfile(
        minAmplitude = 1.3f,
        minRepDurationMs = 500L,
        maxRepDurationMs = 2600L,
        cooldownMs = 700L,
        sampleCount = 5,
        softestSampleAmplitude = 2.0f,
        loudestSampleAmplitude = 2.5f,
        shortestSampleMs = 800L,
        longestSampleMs = 1500L,
        fastestSampleGapMs = 1000L,
        notes = emptyList()
    )

    @Test
    fun `rep matching calibration scores near full marks`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 2.1f)

        val result = scorer.score(rep, profile)

        assertTrue(result.score >= 9f, "expected a high score, got ${result.score}")
        assertTrue(result.reasons.contains("good depth"))
        assertTrue(result.reasons.contains("good tempo"))
    }

    @Test
    fun `shallow rep is marked not deep enough and loses points`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 1.0f) // half of softestSampleAmplitude

        val result = scorer.score(rep, profile)

        assertTrue(result.reasons.contains("not deep enough"))
        assertTrue(result.score < 9f, "expected points lost for shallow depth, got ${result.score}")
    }

    @Test
    fun `rep faster than the shortest calibrated rep is marked rushed`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 400L, amplitude = 2.1f) // faster than shortestSampleMs = 800

        val result = scorer.score(rep, profile)

        assertTrue(result.reasons.contains("rushed"))
    }

    @Test
    fun `rep slower than the longest calibrated rep is marked slower than usual`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 2000L, amplitude = 2.1f) // slower than longestSampleMs = 1500

        val result = scorer.score(rep, profile)

        assertTrue(result.reasons.contains("slower than usual"))
    }

    @Test
    fun `rep much deeper than the calibration reference is not flagged as inconsistent`() {
        // Was "inconsistent amplitude against session history is flagged" before the
        // 2026-09-16 one-sided fix (see Mohit's squat_10_pocket finding, team chat). A rep
        // deeper than calibration is not a quality problem, so this scenario should now NOT
        // be flagged -- being more forceful than your softest calibration rep is fine.
        val previous = listOf(
            RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 2.0f),
            RepEvent(index = 1, startMs = 2000L, endMs = 3100L, amplitude = 2.1f)
        )
        val current = RepEvent(index = 2, startMs = 4000L, endMs = 5100L, amplitude = 4.5f) // way bigger than the other two, but deeper is fine

        val result = scorer.score(current, profile, previousReps = previous)

        assertTrue(!result.reasons.contains("inconsistent with your calibrated depth"))
    }

    @Test
    fun `climbing amplitudes above the calibration reference are not flagged inconsistent`() {
        // Regression test for Mohit's squat_10_pocket finding (team chat, 2026-09-15 evening):
        // 10 genuinely correct squats with amplitude climbing 1.28 -> 2.02 as the lifter
        // "settles into" the movement. Every rep is at or above the calibration reference
        // (1.17), so none of them should count as inconsistent under the one-sided formula --
        // the old symmetric formula flagged 5 of these 10 reps as the average grew away from
        // 1.17.
        val closeProfile = profile.copy(softestSampleAmplitude = 1.17f)
        val amplitudes = listOf(1.28f, 1.45f, 1.62f, 1.78f, 1.90f, 1.95f, 1.98f, 2.00f, 2.01f)
        val previous = amplitudes.mapIndexed { i, amp ->
            RepEvent(index = i, startMs = i * 2000L, endMs = i * 2000L + 1000L, amplitude = amp)
        }
        val current = RepEvent(index = 9, startMs = 18000L, endMs = 19000L, amplitude = 2.02f)

        val result = scorer.score(current, closeProfile, previousReps = previous)

        assertTrue(!result.reasons.contains("inconsistent with your calibrated depth"))
    }

    @Test
    fun `too few previous reps skips the consistency check`() {
        val previous = listOf(RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 2.0f))
        val current = RepEvent(index = 1, startMs = 2000L, endMs = 3100L, amplitude = 4.5f)

        val result = scorer.score(current, profile, previousReps = previous)

        assertTrue(!result.reasons.contains("inconsistent with your calibrated depth"))
    }

    @Test
    fun `uniformly shallow session is flagged even though the reps match each other`() {
        // Regression test for Mohit's PR #3 review comment (2026-09-14): all three reps are
        // within 10% of each other (internally "consistent"), but all sit around half of
        // softestSampleAmplitude (2.0f) -- i.e. a uniformly bad set. The old formula compared
        // reps only to their own session mean/spread and would have missed this entirely
        // (near-zero internal spread); scoring against the calibration reference instead
        // catches it. Still holds under the 2026-09-16 one-sided formula, since every rep
        // here is shallower than the reference, not deeper.
        val previous = listOf(
            RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 1.0f),
            RepEvent(index = 1, startMs = 2000L, endMs = 3100L, amplitude = 1.05f)
        )
        val current = RepEvent(index = 2, startMs = 4000L, endMs = 5100L, amplitude = 0.95f)

        val result = scorer.score(current, profile, previousReps = previous)

        assertTrue(result.reasons.contains("inconsistent with your calibrated depth"))
    }

    @Test
    fun `pauseSeconds is always zero for now`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 2.1f)

        val result = scorer.score(rep, profile)

        assertEquals(0f, result.pauseSeconds)
    }

    // --- 2026-09-15: uncalibrated users no longer get a depth or consistency score --------
    // See FormScorer's class KDoc for the full rationale (Mohit's 43-rep, 4-participant real
    // data check found ~9x amplitude variation between users -- no single placeholder is
    // fair). These replace the old "works without a calibration profile using the fallback
    // reference" test, which asserted only `result.score in 0f..10f` under the now-removed
    // FALLBACK_REFERENCE_AMPLITUDE behaviour.

    @Test
    fun `uncalibrated user gets no depth score, only a sentinel range and an explanatory reason`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 1.5f)

        val result = scorer.score(rep, profile = null)

        assertEquals(FormScorer.NOT_MEASURABLE_RANGE_PERCENT, result.rangePercent)
        assertTrue(result.reasons.contains(FormScorer.UNCALIBRATED_DEPTH_REASON))
        assertTrue(!result.reasons.contains("good depth"))
        assertTrue(!result.reasons.contains("not deep enough"))
    }

    @Test
    fun `uncalibrated user skips consistency check even with plenty of history`() {
        val previous = listOf(
            RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 2.0f),
            RepEvent(index = 1, startMs = 2000L, endMs = 3000L, amplitude = 2.1f),
            RepEvent(index = 2, startMs = 4000L, endMs = 5000L, amplitude = 1.9f)
        )
        val current = RepEvent(index = 3, startMs = 6000L, endMs = 7000L, amplitude = 8.0f) // wildly different

        val result = scorer.score(current, profile = null, previousReps = previous)

        assertTrue(!result.reasons.contains("inconsistent with your calibrated depth"))
    }

    @Test
    fun `uncalibrated tempo fallback window is tightened to 1800ms`() {
        // 1900ms is slower than the new 1800ms fallback ceiling but would NOT have tripped
        // the old 2000ms ceiling -- a direct regression check for the tightened window.
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1900L, amplitude = 1.5f)

        val result = scorer.score(rep, profile = null)

        assertTrue(result.reasons.contains("slower than usual"))
    }
}