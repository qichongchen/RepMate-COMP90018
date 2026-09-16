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
 * against *this user's own* calibration set when one exists.
 *
 * ## 2026-09-15 change: uncalibrated users no longer get a depth or consistency score
 * A first pass used a fixed placeholder amplitude (FALLBACK_REFERENCE_AMPLITUDE, now
 * removed) for users with no [CalibrationProfile]. Mohit re-measured all 43 recorded reps
 * across 4 participants and found genuine squat amplitude spans roughly 1.03-9.05 (about a
 * 9x range) -- no single placeholder can be fair to all of them; a fixed low value makes a
 * strong squatter (e.g. amplitude ~8) look permanently "too deep", and a fixed high value
 * makes a light squatter (e.g. amplitude ~1.2) look permanently "not deep enough". Rather
 * than show a number known to be unreliable for some users -- and risk someone changing an
 * already-correct squat to chase a misleading score -- depth is no longer scored at all for
 * uncalibrated users. rangePercent is set to the sentinel [NOT_MEASURABLE_RANGE_PERCENT]
 * and [RepScore.reasons] carries [UNCALIBRATED_DEPTH_REASON] instead of "good depth" /
 * "not deep enough". This mirrors the existing precedent for [RepScore.pauseSeconds]: an
 * honest sentinel rather than a number that looks precise but is not measurable.
 *
 * Consistency is gated the same way, since it is anchored to the same
 * [CalibrationProfile.softestSampleAmplitude] reference depth scoring uses (see the PR #3
 * review fix) -- an uncalibrated user has no reliable reference for that check either.
 *
 * Tempo is unaffected by calibration status in structure, but the fallback window used when
 * there is no profile was tightened from 600-2000ms to 600-1800ms per the same real-data
 * check (pooled real reps ran 615-3153ms, with the 3153ms sample a likely outlier).
 *
 * ## What this covers, and what it deliberately does not
 * - **Depth**: only scored when [CalibrationProfile] is present, against
 *   [CalibrationProfile.softestSampleAmplitude].
 * - **Tempo**: rep duration compared against the
 *   [CalibrationProfile.shortestSampleMs]/[CalibrationProfile.longestSampleMs] window when
 *   calibrated, or [FALLBACK_SHORTEST_MS]/[FALLBACK_LONGEST_MS] when not -- tempo is scored
 *   either way, since there is no per-user reference needed the way depth needs one.
 * - **Consistency**: only scored when calibrated, anchored to the same calibration
 *   reference depth scoring uses rather than the session's own mean/spread -- an earlier
 *   version compared reps only to each other, which let a uniformly shallow set (near-zero
 *   internal spread) score as "consistent" even though every rep was equally far from a
 *   good depth. (Caught in PR #3 review by Mohit, 2026-09-14 -- see the regression test in
 *   FormScorerTest.)
 * - **Pause is NOT implemented.** [RepEvent] only carries `startMs`/`endMs`/`amplitude` for
 *   the whole movement burst. [SquatRepDetector] cannot separate descent from ascent (its
 *   own KDoc explains why -- magnitude is direction-blind), so there is no timestamp
 *   anywhere for "arrived at the bottom" or "started driving back up" to measure a dwell
 *   time between. [RepScore.pauseSeconds] is always `0f` here as an explicit placeholder,
 *   not a bug -- see the NOTE on [score]. This needs new detector output (gyroscope or a
 *   gravity-vector estimate) before it can be real; that is a question for Mohit, not
 *   something this class can fix alone.
 *
 * This is a **first pass**. The weighting constants in the companion object are a
 * reasonable starting point, not a value agreed with the rest of the team -- bring them to
 * Mohit rather than treating them as settled.
 *
 * Pure Kotlin, no Android dependencies, stateless -- every call is independent.
 */
class FormScorer {

    /**
     * Scores one rep.
     *
     * @param rep the detected rep to score.
     * @param profile the user's calibration, or `null` if they have not calibrated yet.
     *   When `null`, depth and consistency are not scored (see class KDoc, 2026-09-15
     *   change) and tempo falls back to a fixed window ([FALLBACK_SHORTEST_MS]/
     *   [FALLBACK_LONGEST_MS]).
     * @param previousReps reps already scored earlier in the *same* session, oldest first.
     *   Pass an empty list if there is no history yet (e.g. the first rep of a set). Used
     *   only for the consistency check, and ignored entirely when [profile] is `null`.
     *
     * NOTE: [RepScore.pauseSeconds] is always 0f -- see the class KDoc. Known gap, not a
     * bug; flagged here so it is not missed when this function is revisited.
     */
    fun score(
        rep: RepEvent,
        profile: CalibrationProfile?,
        previousReps: List<RepEvent> = emptyList()
    ): RepScore {
        val reasons = mutableListOf<String>()
        var score = MAX_SCORE

        // --- Depth (range of motion) -----------------------------------------------------
        // Only scored when calibrated -- see class KDoc, 2026-09-15 change. No single
        // placeholder reference is fair across users (real amplitude spans ~9x), so an
        // uncalibrated user gets an honest "not scored" sentinel instead of a number that
        // looks precise but may be badly wrong for them.
        val rangePercent: Int
        if (profile != null) {
            val referenceAmplitude = profile.softestSampleAmplitude
            val depthRatio = rep.amplitude / referenceAmplitude
            rangePercent = (depthRatio * 100).roundToInt().coerceIn(0, MAX_RANGE_PERCENT)

            if (depthRatio < 1f) {
                val shortfall = (1f - depthRatio).coerceIn(0f, 1f)
                score -= shortfall * DEPTH_WEIGHT
                reasons += "not deep enough"
            } else {
                reasons += "good depth"
            }
        } else {
            rangePercent = NOT_MEASURABLE_RANGE_PERCENT
            reasons += UNCALIBRATED_DEPTH_REASON
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
        // own mean/spread -- see class KDoc for why that distinction matters). Only
        // meaningful when calibrated, since it anchors to the same reference depth scoring
        // uses -- see class KDoc, 2026-09-15 change. ------------------------------------
        if (profile != null && previousReps.size >= MIN_HISTORY_FOR_CONSISTENCY) {
            val referenceAmplitude = profile.softestSampleAmplitude
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

        /**
         * Sentinel value for [RepScore.rangePercent] when depth is not scored (no
         * [CalibrationProfile]). Mirrors the existing [RepScore.pauseSeconds] = 0f
         * precedent -- a documented "not measurable" marker, not a real percentage.
         * Callers (UI/ViewModel) should check for this value before displaying a depth
         * percentage.
         */
        const val NOT_MEASURABLE_RANGE_PERCENT = -1

        /** Reason string used instead of "good depth" / "not deep enough" when [score] is
         * called with `profile = null`. */
        const val UNCALIBRATED_DEPTH_REASON = "not calibrated: depth not scored"

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

        // Used only when the user has no CalibrationProfile yet, for tempo only -- depth no
        // longer has a fallback (see class KDoc, 2026-09-15 change: no single placeholder
        // amplitude is fair given ~9x real variation between users).
        // Range tightened from 600-2000ms to 600-1800ms per Mohit's 43-rep real-data check
        // (2026-09-15): pooled real reps ran 615-3153ms, with 3153ms a likely outlier from a
        // long pause before the rep was cut off.
        private const val FALLBACK_SHORTEST_MS = 600L
        private const val FALLBACK_LONGEST_MS = 1800L
    }
}