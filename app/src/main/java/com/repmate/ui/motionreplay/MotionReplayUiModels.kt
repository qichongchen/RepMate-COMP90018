package com.repmate.ui.motionreplay

import com.repmate.data.memory.PushupRepTrace
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import com.repmate.engine.FormScorer
import com.repmate.engine.PushupRepDetector
import com.repmate.engine.ReplayedSession
import com.repmate.engine.WorkoutSession
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

    /**
     * A push-up rep, charted as the **elbow angle** through the rep rather than acceleration.
     *
     * Push-ups are counted from the camera and the phone is deliberately held still, so there is
     * no acceleration curve to show -- the movement is the angle. [angleBand] is the detector's
     * own straight/bent pair: a rep has to drop below the lower line and come back above the
     * upper one to count, so the band shows what the rep had to achieve, the way the calibration
     * band does for the IMU exercises.
     *
     * @property bottomDegrees how deep the rep went, or null if the angle could not be read.
     */
    data class PushupAngle(
        override val repIndex: Int,
        override val repCount: Int,
        override val tempoSeconds: Float,
        val curve: List<Pair<Long, Float>>,
        val angleBand: ClosedFloatingPointRange<Float>,
        val bottomDegrees: Double?,
        val score: Float,
        val reasons: List<String>,
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

/**
 * The push-up report: each counted rep's elbow-angle curve beside the score that rep was given.
 *
 * Pairs [traces] with [WorkoutSession.reps] by position -- both are produced in rep order by the
 * same set -- and stops at whichever is shorter, so a capped or partially readable trace reports
 * the reps it does have rather than nothing. Returns null when there is nothing to chart, which
 * leaves the screen on its honest no-replay notice.
 */
fun pushupReplayUiState(
    session: WorkoutSession,
    traces: List<PushupRepTrace>,
): MotionReplayUiState? {
    if (traces.isEmpty()) return null

    val band = PushupRepDetector.DEFAULT_DOWN_DEGREES.toFloat()..PushupRepDetector.DEFAULT_UP_DEGREES.toFloat()
    val reps =
        traces.mapIndexedNotNull { index, trace ->
            val score = session.reps.getOrNull(index) ?: return@mapIndexedNotNull null
            MotionReplayRepUi.PushupAngle(
                repIndex = trace.repIndex,
                repCount = traces.size,
                tempoSeconds = score.tempoSeconds,
                curve = trace.curve,
                angleBand = band,
                bottomDegrees = trace.bottomDegrees,
                score = score.score,
                reasons = score.reasons,
            )
        }

    if (reps.isEmpty()) return null
    return MotionReplayUiState(session.id, session.exercise, reps, isReplayAvailable = true)
}

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