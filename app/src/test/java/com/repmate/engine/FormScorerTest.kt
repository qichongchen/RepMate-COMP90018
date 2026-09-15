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
    fun `inconsistent amplitude against session history is flagged`() {
        val previous = listOf(
            RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 2.0f),
            RepEvent(index = 1, startMs = 2000L, endMs = 3100L, amplitude = 2.1f)
        )
        val current = RepEvent(index = 2, startMs = 4000L, endMs = 5100L, amplitude = 4.5f) // way bigger than the other two

        val result = scorer.score(current, profile, previousReps = previous)

        assertTrue(result.reasons.contains("inconsistent with your calibrated depth"))
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
        // catches it.
        val previous = listOf(
            RepEvent(index = 0, startMs = 0L, endMs = 1100L, amplitude = 1.0f),
            RepEvent(index = 1, startMs = 2000L, endMs = 3100L, amplitude = 1.05f)
        )
        val current = RepEvent(index = 2, startMs = 4000L, endMs = 5100L, amplitude = 0.95f)

        val result = scorer.score(current, profile, previousReps = previous)

        assertTrue(result.reasons.contains("inconsistent with your calibrated depth"))
    }

    @Test
    fun `works without a calibration profile using the fallback reference`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 1.5f)

        val result = scorer.score(rep, profile = null)

        // Should not throw, and should still produce a score in range.
        assertTrue(result.score in 0f..10f)
    }

    @Test
    fun `pauseSeconds is always zero for now`() {
        val rep = RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 2.1f)

        val result = scorer.score(rep, profile)

        assertEquals(0f, result.pauseSeconds)
    }
}
