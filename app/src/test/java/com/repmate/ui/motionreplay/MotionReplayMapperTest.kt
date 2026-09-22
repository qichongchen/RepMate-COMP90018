package com.repmate.ui.motionreplay

import com.repmate.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionReplayMapperTest {

    @Test
    fun `rebaseCurve shifts the first sample to zero`() {
        val curve = listOf(
            SmoothedSample(tMillis = 4200L, magnitude = 10.1f),
            SmoothedSample(tMillis = 4450L, magnitude = 11.3f),
            SmoothedSample(tMillis = 4700L, magnitude = 9.8f),
        )

        val rebased = rebaseCurve(curve, repStartMs = 4200L)

        assertEquals(0L, rebased[0].first)
        assertEquals(250L, rebased[1].first)
        assertEquals(500L, rebased[2].first)
    }

    @Test
    fun `repMinMagnitude finds the lowest point in this rep's own curve`() {
        val curve = listOf(
            SmoothedSample(tMillis = 0L, magnitude = 10.5f),
            SmoothedSample(tMillis = 100L, magnitude = 8.9f),
            SmoothedSample(tMillis = 200L, magnitude = 11.2f),
        )

        assertEquals(8.9f, repMinMagnitude(curve), 0.001f)
    }

    @Test
    fun `toMotionReplayUiState maps an uncalibrated rep without touching score or curve`() {
        val event = RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 1.5f)
        val score = RepScore(
            repIndex = 0,
            score = 0f,
            tempoSeconds = 1f,
            rangePercent = FormScorer.NOT_MEASURABLE_RANGE_PERCENT,
            pauseSeconds = 0f,
            reasons = listOf("not calibrated yet")
        )
        val curve = listOf(SmoothedSample(0L, 9.8f), SmoothedSample(1000L, 10.9f))
        val session = WorkoutSession(id = "s1", exercise = ExerciseType.SQUAT, startedAt = 0L, reps = listOf(score))
        val replayed = ReplayedSession(session, listOf(ReplayedRep(event, score, curve)))

        val uiState = replayed.toMotionReplayUiState(profile = null)

        val rep = uiState.reps.single()
        assertTrue(rep is MotionReplayRepUi.Uncalibrated)
        assertEquals(1, rep.repIndex)
        assertEquals(1f, rep.tempoSeconds, 0.001f)
    }

    @Test
    fun `calibrationBand shifts the profile's softest-loudest range onto this rep's own curve minimum`() {
        val profile = CalibrationProfile(
            minAmplitude = 1f,
            minRepDurationMs = 600L,
            maxRepDurationMs = 1800L,
            cooldownMs = 300L,
            sampleCount = 5,
            softestSampleAmplitude = 1.0f,
            loudestSampleAmplitude = 3.0f,
            shortestSampleMs = 700L,
            longestSampleMs = 1200L,
            fastestSampleGapMs = 200L,
            notes = emptyList()
        )

        val band = calibrationBand(profile, repMinMagnitude = 5.0f)

        assertEquals(6.0f, band.start, 0.001f)
        assertEquals(8.0f, band.endInclusive, 0.001f)
    }

    @Test
    fun `toMotionReplayUiState maps a calibrated rep with a real calibration band`() {
        val profile = CalibrationProfile(
            minAmplitude = 1f,
            minRepDurationMs = 600L,
            maxRepDurationMs = 1800L,
            cooldownMs = 300L,
            sampleCount = 5,
            softestSampleAmplitude = 1.0f,
            loudestSampleAmplitude = 3.0f,
            shortestSampleMs = 700L,
            longestSampleMs = 1200L,
            fastestSampleGapMs = 200L,
            notes = emptyList()
        )
        val event = RepEvent(index = 0, startMs = 0L, endMs = 1000L, amplitude = 2.3f)
        val score = RepScore(
            repIndex = 0,
            score = 8.5f,
            tempoSeconds = 1f,
            rangePercent = 92,
            pauseSeconds = 0f,
            reasons = listOf("good depth")
        )
        val curve = listOf(SmoothedSample(0L, 9.8f), SmoothedSample(1000L, 10.9f))
        val session = WorkoutSession(id = "s1", exercise = ExerciseType.SQUAT, startedAt = 0L, reps = listOf(score))
        val replayed = ReplayedSession(session, listOf(ReplayedRep(event, score, curve)))

        val uiState = replayed.toMotionReplayUiState(profile = profile)

        val rep = uiState.reps.single()
        assertTrue(rep is MotionReplayRepUi.Calibrated)
        rep as MotionReplayRepUi.Calibrated
        assertEquals(9.8f + 1.0f, rep.calibrationBand.start, 0.001f)
        assertEquals(9.8f + 3.0f, rep.calibrationBand.endInclusive, 0.001f)
    }
}