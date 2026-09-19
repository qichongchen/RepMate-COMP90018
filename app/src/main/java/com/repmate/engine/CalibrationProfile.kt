package com.repmate.engine

/**
 * Per-user detection thresholds, derived from a short calibration set.
 *
 * ## Why absolute thresholds do not transfer
 * [SquatRepDetector]'s defaults are absolute numbers in m/s^2 and milliseconds, tuned on
 * recorded traces. Across the four recordings in the library the same exercise, performed
 * correctly, produces wildly different signals:
 *
 * ```
 *   participant   softest rep   shortest rep   longest rep   fastest gap
 *   Hit                  1.03         899 ms       1573 ms        2782 ms
 *   Mohit (normal)       1.17         615 ms        987 ms        2943 ms
 *   Mohit (fast)         2.92         665 ms        957 ms         917 ms
 *   Lisa                 7.31         643 ms        814 ms        1034 ms
 * ```
 *
 * Amplitude spans **7.1x** between Hit and Lisa. A single [SquatRepDetector.minAmplitude]
 * has to sit under 1.03 to keep Hit's softest rep and over Hit's 0.83 wobble — a band
 * 0.15 wide — while Lisa's reps sit eight times above it and constrain nothing. The floor
 * is therefore set by whichever participant happens to squat softest. Thresholds derived
 * from the user's own movement do not have that coupling.
 *
 * ## How the margins were chosen
 * Every multiplier is derived from what the four traces do, not picked for roundness. The
 * evidence is the **drift between the first five reps of a set and the last five** —
 * precisely the extrapolation a calibration set has to survive:
 *
 * ```
 *   trace            amplitude   shortest   longest   fastest gap
 *   squat_10_fast        0.771      1.379     1.202         1.091
 *   squat_10_hit         1.102      1.288     1.214         1.137
 *   squat_10_lisa        0.917      1.187     1.040         0.981
 *   squat_10_pocket      1.287      0.885     1.041         1.236
 * ```
 *
 * (Each cell is `last five ÷ first five` for that statistic.)
 *
 * - [AMPLITUDE_MARGIN] = **0.65**. Worst softening observed is 0.771 (Mohit's fast set fades
 *   4.42 -> 2.92 as he tires). Taking the spread symmetrically in log space, the largest
 *   excursion either way is 1.287, implying a worst plausible softening of 0.777. 0.65 sits
 *   16% below that. Fatigue makes later reps softer, so this margin is the one most likely
 *   to be exercised.
 * - [MIN_DURATION_MARGIN] = **0.75**: the derived floor sits a quarter below the shortest
 *   calibration rep. It was 0.85, fitted tightly to the drift between the halves of the four
 *   recorded sets, and that tight fit was the problem: **a calibration set is a sample, not a
 *   bound.** The shortest of five prompted reps is not the shortest rep the user will
 *   produce, and people squat faster in a real set than in a paced five-rep calibration. A
 *   live run on 2026-09-13 showed exactly that: a natural pace near 1.2 s/rep, with genuine
 *   reps of 380–420 ms rejected on duration while carrying the same amplitude as the reps
 *   that were counted. The upper bound is unchanged — at most 0.885, or Mohit's normal set
 *   loses its 615 ms rep.
 *
 *   **Checked against all four recorded squat traces** (profile derived from reps 1–5, then
 *   replayed over the set window, the whole recording and the quiet window) at 0.70, 0.75,
 *   0.80 and 0.85. All four margins behave identically: 10 of 10 in every set window, nothing
 *   detected in either quiet window, and no window admitted or lost relative to 0.85. On the
 *   data that exists, the extra ten points admit nothing.
 *
 *   **What it gives up is redundancy on Hit's wobble.** This note used to say the margin had
 *   to stay above 0.826 because duration was the *only* guard that could reject Hit's 743 ms,
 *   0.83-amplitude wobble under calibration. That was never true: his derived cooldown is
 *   clamped to 900 ms and the wobble starts 778 ms after the preceding rep, so the cooldown
 *   rejects it as well. At 0.85 both guards did (floor 764 ms). At 0.75 (floor 674 ms) only
 *   the cooldown does, by 122 ms; replayed with the cooldown disabled, the wobble is counted
 *   and Hit's set reads 11. The wobble is still rejected, but by a timing coincidence rather
 *   than by two independent guards: the same wobble arriving more than 900 ms after a rep
 *   would be counted.
 * - [MAX_DURATION_MARGIN] = **2.2**. Must exceed 1.214 (Hit lengthens 1296 -> 1573 ms) and
 *   stay under 4.03, or Lisa's 3153 ms settling burst — the thing
 *   [SquatRepDetector.maxRepDurationMs] exists to abandon — is admitted. 2.2 is the
 *   geometric centre of that band.
 * - [COOLDOWN_MARGIN] = **0.75**. Only bounded above: a cooldown longer than the user's real
 *   gap eats reps, the bug that dropped the original 2000 ms cooldown. Worst contraction
 *   observed is 0.981; the symmetric worst case is 0.809; 0.75 sits below with 7% to spare.
 *
 * ## ⚠️ What a lower duration margin does not fix
 * **In live testing the margin recovered 1 of 4 missed reps.** It is worth having, and it is
 * not the fix. Apart from the duration floor, covered below, the remaining gap is not a
 * threshold problem.
 *
 * **Calibration and performance were two different populations.** In a 2026-09-14 session the
 * user's paced calibration reps averaged an amplitude of **7.0**; the set counted minutes
 * later, by the same person on the same phone, averaged **1.3** and peaked at **1.99**. The
 * calibration reps were slow and deep, the real set fast and shallow. Every threshold in a
 * profile is derived from the first population and then applied to the second — the
 * amplitude floor as much as the duration floor — and a margin sized for drift *within* a set
 * is not built to bridge a gap *between* two kinds of rep. That mismatch has to be solved
 * where it arises, by guidance: telling the user to calibrate at the pace and depth they
 * actually train at. The margin and that guidance address different halves of the same
 * problem; they are complementary, not alternatives.
 *
 * **Shallow reps also sit close to the trigger itself.** A rep that clears the 10.15 open
 * threshold by only a little is at the mercy of the trigger, and a rep that does not clear it
 * never opens a window for any guard to judge. Calibration cannot help there: the trigger
 * thresholds are positions relative to gravity and are deliberately not calibrated (see
 * `SquatRepDetector(profile)`). The recorded traces already showed this failure mode: Hit's
 * softest rep replayed at half force peaks at 10.26 against the 10.15 threshold. For
 * contrast, the same user's reps in a 2026-09-13 run measured 3.06–6.74.
 *
 * **The one threshold problem was the duration floor.** [MIN_REP_DURATION_FLOOR_MS] clamps
 * every derived duration floor up to a fixed minimum. At 400 ms that minimum rejected genuine
 * live reps of 300 and 320 ms whatever calibration derived: a calibration whose shortest rep
 * was 380 ms derived 285 ms and was clamped straight back to 400. It is now 280 ms; see that
 * constant for the measurements and for what a floor that low admits. Lowering it removes a
 * block rather than guaranteeing a count, because the reps still have to clear whatever
 * calibration derives.
 *
 * All live figures come from probe logs, not committed fixtures, so none can be reproduced
 * from this repository.
 *
 * ## Safety bounds
 * Margins protect against a user drifting during a set. They do **not** protect against a
 * bad calibration set — five "reps" that were not five clean squats. Two independent
 * mechanisms handle that, and they fail in opposite directions on purpose:
 *
 * 1. **Rejection** ([evaluate]) when the five samples disagree with each other. A genuine
 *    set is internally consistent; noise is not. Nothing is derived at all, and the caller
 *    asks the user to repeat the calibration.
 * 2. **Clamping** when a derived value leaves its plausible range. This catches sets that
 *    are self-consistent but wrong — five identical bounces, say — where rejection has
 *    nothing to trigger on. A clamped value is recorded in [notes] rather than hidden.
 *
 * If both are passed the profile is used; if rejection fires the caller falls back to the
 * tuned defaults, which is what `SquatRepDetector(profile = null)` already does.
 *
 * ## ⚠️ What the consistency gate does not catch
 * The gate is sized from real sets: the widest genuine spreads in the library are **1.81x**
 * in amplitude (Hit) and **1.44x** in duration (Hit). The limits below sit well clear of
 * those. But a calibration polluted by Hit's wobble spreads only **2.24x / 1.58x** — inside
 * both limits. **The gate would not reject it.** Pollution of that kind has to be prevented
 * at capture time. The gate is a backstop against gross nonsense, not a substitute for
 * capturing the right reps.
 *
 * ## How capture prevents it: permissive guards, not prompted windows
 * This note used to say the prevention was a *prompted rep window* — cue each rep, measure
 * only inside the cue. That is superseded by [SquatRepDetector.forCalibrationCapture], for
 * three reasons:
 *
 * 1. **Prompting produces the wrong population.** The failure described above — calibration
 *    reps averaging 7.0 against a real set's 1.3 — is what paced, cued reps look like: slow
 *    and deep, performed *for* the prompt. A per-rep cue invites exactly that. Capture now
 *    runs over one continuous set at the user's own pace, and the screen tells them to rep
 *    as they would in a workout.
 * 2. **Prompting writes our timing into the profile.** [fastestSampleGapMs] and the durations
 *    would measure the cue's rhythm rather than the user's, and the derived [cooldownMs] and
 *    duration floor with them.
 * 3. **The pollution came from loosening amplitude, which capture does not do.** Hit's
 *    wobble swings 0.83; the capture detector keeps the default 0.94 floor, so the wobble
 *    never reaches this gate. Only the duration floor is lowered, and the reasons each other
 *    guard stays at its default — Lisa's rebounds among them — are measured in that factory's
 *    documentation and pinned in `CalibrationCaptureTest`.
 *
 * What survives of the prompted-window idea is the window around the *whole set*: capture
 * starts after a countdown, so the phone going into the pocket is never measured, and stops
 * at the fifth rep.
 *
 * Pure Kotlin, no Android dependencies.
 *
 * @property minAmplitude derived floor for [SquatRepDetector.minAmplitude], m/s^2.
 * @property minRepDurationMs derived floor for [SquatRepDetector.minRepDurationMs].
 * @property maxRepDurationMs derived ceiling for [SquatRepDetector.maxRepDurationMs].
 * @property cooldownMs derived value for [SquatRepDetector.cooldownMs].
 * @property sampleCount how many reps the profile was derived from.
 * @property softestSampleAmplitude raw measurement each threshold is traceable to, kept so a
 *   profile can be explained rather than merely applied.
 * @property shortestSampleMs raw shortest sample duration.
 * @property longestSampleMs raw longest sample duration.
 * @property fastestSampleGapMs raw shortest gap between consecutive samples.
 * @property notes one line per derived value, saying what it came from and whether a clamp
 *   changed it. Intended to be logged whole: a miscounting user's profile is diagnosable
 *   from this without reproducing their session.
 */
data class CalibrationProfile(
    val minAmplitude: Float,
    val minRepDurationMs: Long,
    val maxRepDurationMs: Long,
    val cooldownMs: Long,
    val sampleCount: Int,
    val softestSampleAmplitude: Float,
    val shortestSampleMs: Long,
    val longestSampleMs: Long,
    val fastestSampleGapMs: Long,
    val notes: List<String>
) {

    /** True if any derived value hit a safety bound; such a profile is worth looking at. */
    val wasClamped: Boolean get() = notes.any { it.contains("CLAMPED") }

    /** Multi-line record of what was derived and why, for logging. */
    fun describe(): String = buildString {
        appendLine("CalibrationProfile from $sampleCount reps" + if (wasClamped) " (CLAMPED)" else "")
        appendLine("  samples: softest %.2f, durations %d-%d ms, fastest gap %d ms"
            .format(softestSampleAmplitude, shortestSampleMs, longestSampleMs, fastestSampleGapMs))
        notes.forEach { appendLine("  $it") }
    }.trimEnd()

    companion object {
        /** Reps required before a profile may be derived. */
        const val REQUIRED_SAMPLES = 5

        // --- Margins (see the class documentation for the derivation of each) -------------
        const val AMPLITUDE_MARGIN = 0.65f
        const val MIN_DURATION_MARGIN = 0.75
        const val MAX_DURATION_MARGIN = 2.2
        const val COOLDOWN_MARGIN = 0.75

        // --- Consistency limits ----------------------------------------------------------
        /**
         * Largest amplitude spread (loudest ÷ softest) a calibration set may show.
         *
         * The widest genuine set in the library is Hit's first five at **1.81x**. 3.0 leaves
         * that a 66% margin, so ordinary unevenness is never rejected, while five samples
         * spanning more than 3x are not five of the same movement.
         */
        const val MAX_AMPLITUDE_SPREAD = 3.0f

        /**
         * Largest duration spread (longest ÷ shortest) a calibration set may show.
         *
         * The widest genuine set is again Hit's, at **1.44x**. 2.0 leaves a 39% margin.
         */
        const val MAX_DURATION_SPREAD = 2.0

        // --- Safety bounds ---------------------------------------------------------------
        // Each range is bounded by physical plausibility on one side and by what the four
        // traces actually produce on the other. The derived values from those traces are
        // minAmplitude 0.67-5.18, minRepDuration 482-674, maxRepDuration 1722-2851 and
        // cooldown 747-2207, so only the cooldown ceiling binds on real data today.

        /**
         * Floor for [minAmplitude]. A still phone's smoothed signal swings **0.022** over two
         * seconds, so 0.35 is roughly sixteen times the sensor's own noise; it also sits below
         * the weakest legitimate derivation in the library (Hit's 0.67), so it never binds on
         * a real user. It exists so a calibration captured from a barely-moving phone cannot
         * produce a floor that noise alone would clear.
         */
        const val MIN_AMPLITUDE_FLOOR = 0.35f

        /**
         * Ceiling for [minAmplitude]. The most forceful participant recorded (Lisa) derives
         * 5.18; 8.0 leaves her 54% of headroom. Above that the calibration was measuring
         * impacts rather than squats, and applying it would reject the user's real reps.
         */
        const val MIN_AMPLITUDE_CEILING = 8.0f

        /**
         * Floor for [minRepDurationMs]: **280 ms**.
         *
         * It was 400, on the premise that a squat's movement burst does not complete in under
         * 400 ms. Every recorded trace agrees (the shortest recorded rep is 615 ms); live testing
         * does not. Genuine live reps measured **300 ms** and **320 ms**, and a 400 ms floor
         * rejects them whatever calibration derives, because it clamps any lower derived value
         * back up. No margin, and no amount of calibrating at a natural pace, could have
         * recovered them. 280 sits 20 ms under the shortest genuine rep measured.
         *
         * **Necessary, not sufficient.** The floor only stops blocking such reps; whether one is
         * counted still depends on what calibration derives. A 300 ms rep survives only if the
         * shortest calibration rep is 400 ms or less (400 x 0.75 = 300). Calibrate at a slower
         * pace — a 500 ms shortest rep, say — and the derived 375 ms rejects both measured reps
         * again. That is the guidance half of the problem described in the class documentation.
         *
         * **What a floor this low admits, checked on the four recorded squat traces.** None of
         * them derives a floor anywhere near it (482–674 ms), so through calibration the change
         * alters nothing on them. To see what a detector actually running at 280 ms lets in, each
         * trace was replayed at that value directly. Every set window still counts 10 of 10 and
         * both quiet windows stay empty: no spurious rep appears inside a set. Over whole
         * recordings it admits phone-handling bursts outside the set — with default guards, one
         * on `squat_10_fast` (282 ms, amplitude 1.24) and one on `squat_10_lisa` (312 ms, 1.01);
         * with calibrated guards, two on `squat_10_hit` before its set starts (553 ms, 0.86 and
         * 588 ms, 7.03). Trimming handling bursts is a session-boundary job rather than this
         * guard's, so that is the accepted cost.
         */
        const val MIN_REP_DURATION_FLOOR_MS = 280L

        /**
         * Ceiling for [minRepDurationMs]. A floor above this would reject ordinary reps: it
         * can only be reached from calibration reps of 1.6 s or longer, and the guard would
         * then be stricter than the slowest rep in the library (1573 ms) leaves room for.
         */
        const val MIN_REP_DURATION_CEILING_MS = 1200L

        /**
         * Floor for [maxRepDurationMs]. Sits just above the longest genuine rep recorded
         * (1573 ms), so a ceiling can never be derived low enough to abandon a rep as long as
         * any we have actually seen.
         */
        const val MAX_REP_DURATION_FLOOR_MS = 1600L

        /**
         * Ceiling for [maxRepDurationMs], matching [SquatRepDetector.DEFAULT_MAX_REP_DURATION_MS].
         * Lisa's settling burst — the stuck window this guard exists to abandon — runs
         * **3153 ms**, so a ceiling above 3000 would start admitting exactly that failure.
         */
        const val MAX_REP_DURATION_CEILING_MS = 3000L

        /** Floor for [cooldownMs]. Below this it stops suppressing a counted rep's rebound. */
        const val COOLDOWN_FLOOR_MS = 200L

        /**
         * Ceiling for [cooldownMs]. The tightest genuine gap in the library is Mohit's
         * **917 ms** at a fast pace, so a cooldown above that can swallow a real rep from
         * anyone who speeds up after calibrating slowly — which is the 2000 ms bug returning
         * by another route. 900 sits just under it. This is the one bound that binds on real
         * data: Hit (2086 ms) and Mohit's normal set (2207 ms) are both clamped, harmlessly,
         * since their own gaps are near 3 s and the cooldown simply stops binding.
         */
        const val COOLDOWN_CEILING_MS = 900L

        /**
         * Derives a profile, or explains why it could not be trusted.
         *
         * @param samples the captured calibration reps, in chronological order.
         */
        fun evaluate(samples: List<RepEvent>): CalibrationOutcome {
            if (samples.size < REQUIRED_SAMPLES) {
                return CalibrationOutcome.Rejected(
                    CalibrationOutcome.Reason.TOO_FEW_SAMPLES,
                    "got ${samples.size} usable reps, need $REQUIRED_SAMPLES"
                )
            }

            val ordered = samples.sortedBy { it.startMs }
            val amplitudes = ordered.map { it.amplitude }
            val durations = ordered.map { it.endMs - it.startMs }
            val gaps = ordered.zipWithNext { earlier, later -> later.startMs - earlier.endMs }

            if (gaps.isEmpty() || gaps.any { it <= 0L } || durations.any { it <= 0L }) {
                return CalibrationOutcome.Rejected(
                    CalibrationOutcome.Reason.UNUSABLE_TIMING,
                    "samples overlap or have non-positive duration: " +
                        "durations $durations, gaps $gaps"
                )
            }

            val softest = amplitudes.min()
            val loudest = amplitudes.max()
            if (softest <= 0f) {
                return CalibrationOutcome.Rejected(
                    CalibrationOutcome.Reason.UNUSABLE_TIMING,
                    "a sample had non-positive amplitude: $amplitudes"
                )
            }
            val amplitudeSpread = loudest / softest
            if (amplitudeSpread > MAX_AMPLITUDE_SPREAD) {
                return CalibrationOutcome.Rejected(
                    CalibrationOutcome.Reason.AMPLITUDE_INCONSISTENT,
                    "amplitudes span %.2fx (%.2f to %.2f), limit %.1fx — these are not five of the same movement"
                        .format(amplitudeSpread, softest, loudest, MAX_AMPLITUDE_SPREAD)
                )
            }

            val shortest = durations.min()
            val longest = durations.max()
            val durationSpread = longest.toDouble() / shortest
            if (durationSpread > MAX_DURATION_SPREAD) {
                return CalibrationOutcome.Rejected(
                    CalibrationOutcome.Reason.DURATION_INCONSISTENT,
                    "durations span %.2fx (%d to %d ms), limit %.1fx — these are not five of the same movement"
                        .format(durationSpread, shortest, longest, MAX_DURATION_SPREAD)
                )
            }

            val fastestGap = gaps.min()
            val notes = mutableListOf<String>()

            val minAmplitude = clampF(
                softest * AMPLITUDE_MARGIN, MIN_AMPLITUDE_FLOOR, MIN_AMPLITUDE_CEILING,
                "minAmplitude", "%.2f softest x %.2f".format(softest, AMPLITUDE_MARGIN), notes
            )
            val minRepDurationMs = clampL(
                (shortest * MIN_DURATION_MARGIN).toLong(),
                MIN_REP_DURATION_FLOOR_MS, MIN_REP_DURATION_CEILING_MS,
                "minRepDurationMs", "%d shortest x %.2f".format(shortest, MIN_DURATION_MARGIN), notes
            )
            val maxRepDurationMs = clampL(
                (longest * MAX_DURATION_MARGIN).toLong(),
                MAX_REP_DURATION_FLOOR_MS, MAX_REP_DURATION_CEILING_MS,
                "maxRepDurationMs", "%d longest x %.2f".format(longest, MAX_DURATION_MARGIN), notes
            )
            val cooldownMs = clampL(
                (fastestGap * COOLDOWN_MARGIN).toLong(),
                COOLDOWN_FLOOR_MS, COOLDOWN_CEILING_MS,
                "cooldownMs", "%d fastest gap x %.2f".format(fastestGap, COOLDOWN_MARGIN), notes
            )

            // The ranges above cannot overlap, so this is belt-and-braces against someone
            // widening a bound later without noticing it breaks the detector's invariant.
            check(maxRepDurationMs > minRepDurationMs) {
                "clamp ranges are inconsistent: max $maxRepDurationMs <= min $minRepDurationMs"
            }

            return CalibrationOutcome.Accepted(
                CalibrationProfile(
                    minAmplitude = minAmplitude,
                    minRepDurationMs = minRepDurationMs,
                    maxRepDurationMs = maxRepDurationMs,
                    cooldownMs = cooldownMs,
                    sampleCount = ordered.size,
                    softestSampleAmplitude = softest,
                    shortestSampleMs = shortest,
                    longestSampleMs = longest,
                    fastestSampleGapMs = fastestGap,
                    notes = notes
                )
            )
        }

        /**
         * Convenience wrapper: the profile, or null when calibration was rejected.
         *
         * Null is what `SquatRepDetector(profile)` turns back into the tuned defaults, so a
         * caller that does not care *why* calibration failed still gets a working detector.
         * Anything diagnosing a bad calibration should use [evaluate] instead.
         */
        fun fromSamples(samples: List<RepEvent>): CalibrationProfile? =
            (evaluate(samples) as? CalibrationOutcome.Accepted)?.profile

        private fun clampF(
            raw: Float, floor: Float, ceiling: Float, name: String, from: String,
            notes: MutableList<String>
        ): Float {
            val bounded = raw.coerceIn(floor, ceiling)
            notes += if (bounded == raw) "%s = %.2f (%s)".format(name, raw, from)
            else "%s = %.2f CLAMPED from %.2f (%s), allowed %.2f..%.2f"
                .format(name, bounded, raw, from, floor, ceiling)
            return bounded
        }

        private fun clampL(
            raw: Long, floor: Long, ceiling: Long, name: String, from: String,
            notes: MutableList<String>
        ): Long {
            val bounded = raw.coerceIn(floor, ceiling)
            notes += if (bounded == raw) "$name = $raw ms ($from)"
            else "$name = $bounded ms CLAMPED from $raw ms ($from), allowed $floor..$ceiling"
            return bounded
        }
    }
}

/** The result of trying to derive a [CalibrationProfile] from a calibration set. */
sealed interface CalibrationOutcome {

    /** Calibration succeeded. [CalibrationProfile.notes] records any value that was clamped. */
    data class Accepted(val profile: CalibrationProfile) : CalibrationOutcome

    /**
     * Calibration could not be trusted. The caller should keep using the tuned defaults and
     * ask the user to repeat the calibration; [detail] is written for a log, not for a user.
     */
    data class Rejected(val reason: Reason, val detail: String) : CalibrationOutcome

    /** Why a calibration set was refused. */
    enum class Reason {
        /** Fewer than [CalibrationProfile.REQUIRED_SAMPLES] reps were captured. */
        TOO_FEW_SAMPLES,

        /** Samples overlap, run backwards, or have no duration — a capture bug, not a user error. */
        UNUSABLE_TIMING,

        /** The five samples differ too much in size to be the same movement. */
        AMPLITUDE_INCONSISTENT,

        /** The five samples differ too much in length to be the same movement. */
        DURATION_INCONSISTENT
    }
}
