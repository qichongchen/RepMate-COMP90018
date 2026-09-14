package com.repmate.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns one detected [RepEvent] into a 0-10 [RepScore], judging movement quality
 * **relative to the user's own calibration**, not against a fixed global standard.
 *
 * ## Why relative, not absolute
 * [CalibrationProfile]'s own documentation shows squat amplitude spanning 7.1x between
 * real participants even when both are squatting correctly (see its class KDoc). A single
 * global "good" amplitude cannot be fair to both, so this scorer always measures a rep
 * against *this user's own* calibration set when one exists, and only falls back to a
 * fixed placeholder when it does not.
 *
 * ## What this first version covers, and what it deliberately does not
 * - **Depth**: [rep.amplitude] compared against [CalibrationProfile.softestSampleAmplitude]
 *   — the softest rep the user produced while calibrating is treated as the floor of
 *   "good depth" for them.
 * - **Tempo**: rep duration compared against the
 *   [CalibrationProfile.shortestSampleMs]/[CalibrationProfile.longestSampleMs] window
 *   observed during calibration.
 * - **Consistency**: how far this session's reps deviate, on average, from the *same*
 *   calibration reference depth scoring already uses, once there are at least
 *   [MIN_HISTORY_FOR_CONSISTENCY] of them. Deliberately anchored to the calibration
 *   reference rather than the session's own mean/spread — an earlier version compared reps
 *   only to each other, which let a uniformly shallow set (near-zero internal spread) score
 *   as "consistent" even though every rep was equally far from a good depth. (Caught in
 *   PR #3 review by Mohit, 2026-09-14 — see the regression test in FormScorerTest.)
 * - **Pause is NOT implemented.** [RepEvent] only carries `startMs`/`endMs`/`amplitude` for
 *   the whole movement burst. [SquatRepDetector] cannot separate descent from ascent (its
 *   own KDoc explains why — magnitude is direction-blind), so there is no timestamp
 *   anywhere for "arrived at the bottom" or "started driving back up" to measure a dwell
 *   time between. [RepScore.pauseSeconds] is always `0f` here as an explicit placeholder,
 *   not a bug — see the NOTE on [score]. This needs new detector output (gyroscope or a
 *   gravity-vector estimate) before it can be real; that is a question for Mohit, not
 *   something this class can fix alone.
 *
 * This is a **first pass**. The weighting constants in the companion object are a
 * reasonable starting point, not a value agreed with the rest of the team — bring them to
 * Mohit rather than treating them as settled.
 *
 * Pure Kotlin, no Android dependencies, stateless — every call is independent.
 */
class FormScorer {

    /**
     * Scores one rep.
     *
     * @param rep the detected rep to score.
     * @param profile the user's calibration, or `null` to fall back to a fixed placeholder
     *   reference (see the constants below) — used for scoring only, mirrors the same
     *   null-safe fallback pattern [SquatRepDetector] uses for detection thresholds.
     * @param previousReps reps already scored earlier in the *same* session, oldest first.
     *   Pass an empty list if there is no history yet (e.g. the first rep of a set). Used
     *   only for the consistency check.
     *
     * NOTE: [RepScore.pauseSeconds] is always 0f — see the class KDoc. Known gap, not a
     * bug; flagged here so it is not missed when this function is revisited.
     */
    fun score(
        rep: RepEvent,
        profile: CalibrationProfile?,
        previousReps: List<RepEvent> = emptyList()
    ): RepScore {
        val reasons = mutableListOf<String>()
        var score = MAX_SCORE

        // --- Depth (range of motion) ---------------------------------------------------
        val referenceAmplitude = profile?.softestSampleAmplitude ?: FALLBACK_REFERENCE_AMPLITUDE
        val depthRatio = rep.amplitude / referenceAmplitude
        val rangePercent = (depthRatio * 100).roundToInt().coerceIn(0, MAX_RANGE_PERCENT)

        if (depthRatio < 1f) {
            val shortfall = (1f - depthRatio).coerceIn(0f, 1f)
            score -= shortfall * DEPTH_WEIGHT
            reasons += "not deep enough"
        } else {
            reasons += "good depth"
        }

        // --- Tempo -----------------------------------------------------------------------
        val tempoMs = rep.endMs - rep.startMs
        val tempoSeconds = tempoMs / 1000f
        val shortestMs = profile?.shortestSampleMs ?: FALLBACK_SHORTEST_MS
        val longestMs = profile?.longestSampleMs ?: FALLBACK_LONGEST_MS

        when {
            tempoMs < shortestMs -> {
                val shortfall = ((shortestMs - tempoMs).toFloat() / shortestMs).coerceIn(0f, 1f)
                score -= shortfall * TEMPO_WEIGHT
                reasons += "rushed"
            }
            tempoMs > longestMs -> {
                val excess = ((tempoMs - longestMs).toFloat() / longestMs).coerceIn(0f, 1f)
                score -= excess * TEMPO_WEIGHT
                reasons += "slower than usual"
            }
            else -> reasons += "good tempo"
        }

        // --- Consistency (soft check against the calibration reference, NOT this session's
        // own mean/spread -- see class KDoc for why that distinction matters) --------------
        if (previousReps.size >= MIN_HISTORY_FOR_CONSISTENCY) {
            val amplitudes = previousReps.map { it.amplitude } + rep.amplitude
            val meanRelativeDeviation = amplitudes
                .map { abs(it - referenceAmplitude) / referenceAmplitude }
                .average()
                .toFloat()

            if (meanRelativeDeviation > CONSISTENCY_DEVIATION_THRESHOLD) {
                score -= CONSISTENCY_WEIGHT
                reasons += "inconsistent with your calibrated depth"
            }
        }

        return RepScore(
            repIndex = rep.index,
            score = score.coerceIn(0f, MAX_SCORE),
            tempoSeconds = tempoSeconds,
            rangePercent = rangePercent,
            pauseSeconds = 0f, // NOTE: not measurable from RepEvent yet, see class KDoc
            reasons = reasons
        )
    }

    companion object {
        private const val MAX_SCORE = 10f
        private const val MAX_RANGE_PERCENT = 300

        // How many points depth/tempo/consistency issues can cost, out of 10. These do not
        // have to sum to 10 -- a rep can be both shallow AND rushed at the same time.
        private const val DEPTH_WEIGHT = 4f
        private const val TEMPO_WEIGHT = 3f
        private const val CONSISTENCY_WEIGHT = 1.5f

        private const val MIN_HISTORY_FOR_CONSISTENCY = 2

        // Average relative deviation (|amplitude - referenceAmplitude| / referenceAmplitude)
        // across the session's reps, above which the set is flagged as inconsistent.
        // Anchored to the calibration reference rather than the session's own mean -- see
        // class KDoc. Same numeric value carried over from the first pass; still an
        // unvalidated guess, not checked against real data.
        private const val CONSISTENCY_DEVIATION_THRESHOLD = 0.35f

        // Used only when the user has no CalibrationProfile yet. Not measured from any
        // trace -- a placeholder pending an "uncalibrated user" decision with Mohit.
        // Roughly 1.5x SquatRepDetector.DEFAULT_MIN_AMPLITUDE (0.94f).
        private const val FALLBACK_REFERENCE_AMPLITUDE = 1.4f
        private const val FALLBACK_SHORTEST_MS = 600L
        private const val FALLBACK_LONGEST_MS = 2000L
    }
}
