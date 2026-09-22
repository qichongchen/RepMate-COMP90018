package com.repmate.ui.motionreplay

import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.FormScorer
import com.repmate.engine.ReplayedSession
import com.repmate.engine.SmoothedSample

sealed interface MotionReplayRepUi {
    val repIndex: Int
    val repCount: Int
    val tempoSeconds: Float

    data class Calibrated(
        override val repIndex: Int,
        override val repCount: Int,
        override val tempoSeconds: Float,
        val curve: List<Pair<Long, Float>>,
        val calibrationBand: ClosedFloatingPointRange<Float>,
        val score: Float,
        val rangePercent: Int,
        val reasons: List<String>,
    ) : MotionReplayRepUi

    data class Uncalibrated(
        override val repIndex: Int,
        override val repCount: Int,
        override val tempoSeconds: Float,
    ) : MotionReplayRepUi
}

data class MotionReplayUiState(
    val sessionId: String,
    val exercise: ExerciseType,
    val reps: List<MotionReplayRepUi>,
    val currentIndex: Int = 0,
    val isReplayAvailable: Boolean,
)

/** Rebases a rep's absolute-time curve onto this rep's own timeline, starting at 0. */
internal fun rebaseCurve(curve: List<SmoothedSample>, repStartMs: Long): List<Pair<Long, Float>> =
    curve.map { (it.tMillis - repStartMs) to it.magnitude }

/** The lowest point of this rep's own curve -- the anchor the calibration band is drawn from. */
internal fun repMinMagnitude(curve: List<SmoothedSample>): Float =
    curve.minOf { it.magnitude }

/**
 * The calibration band's [low, high] bounds, anchored to this rep's own curve minimum -- see
 * tracker 32.5 for why calibration amplitude (a swing) can't be plotted as an absolute
 * Y-coordinate directly.
 *
 * Now that [CalibrationProfile.loudestSampleAmplitude] exists (PR #21, merged 2026-09-22), the
 * band is simply this rep's own curve minimum shifted up by the calibration set's softest and
 * loudest recorded amplitudes -- the same [repMinMagnitude] anchor [rebaseCurve]'s caller already
 * uses for the curve itself, so the band and the curve share one coordinate space.
 */
internal fun calibrationBand(profile: CalibrationProfile, repMinMagnitude: Float): ClosedFloatingPointRange<Float> =
    (repMinMagnitude + profile.softestSampleAmplitude)..(repMinMagnitude + profile.loudestSampleAmplitude)

fun ReplayedSession.toMotionReplayUiState(profile: CalibrationProfile?): MotionReplayUiState {
    val reps = this.reps.mapIndexed { i, r ->
        val tempo = (r.event.endMs - r.event.startMs) / 1000f
        if (profile != null && r.score.rangePercent != FormScorer.NOT_MEASURABLE_RANGE_PERCENT) {
            MotionReplayRepUi.Calibrated(
                repIndex = i + 1,
                repCount = this.reps.size,
                tempoSeconds = tempo,
                curve = rebaseCurve(r.curve, r.event.startMs),
                calibrationBand = calibrationBand(profile, repMinMagnitude(r.curve)),
                score = r.score.score,
                rangePercent = r.score.rangePercent,
                reasons = r.score.reasons,
            )
        } else {
            MotionReplayRepUi.Uncalibrated(i + 1, this.reps.size, tempo)
        }
    }
    return MotionReplayUiState(session.id, session.exercise, reps, isReplayAvailable = true)
}