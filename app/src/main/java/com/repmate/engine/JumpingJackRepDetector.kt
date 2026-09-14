package com.repmate.engine

/**
 * Counts jumping jacks by **pairing impacts**, not by counting movement bursts.
 *
 * ## Why this cannot reuse the squat model
 * [SquatRepDetector] assumes one rep is one burst: the body moves, the smoothed magnitude
 * rises above the trigger and falls back, and that span *is* the repetition. A jumping jack
 * breaks that assumption. It produces **two** impacts per repetition — the jump out, then
 * the landing — so a burst-per-rep counter reads a clean 10-rep set as 20.
 *
 * Across the two paced fixtures (`jumpingjack_10_mohit_paced` and `..._fast`: Pixel,
 * ~99 Hz, front pocket, 10 reps each, performed to a metronome) the ratio is exactly
 * **2.0 surviving bursts per rep** in both. The two impacts are also consistently unequal,
 * and in a fixed order:
 *
 * ```
 *   burst 1 (jump out)   amplitude  9.05 - 11.72   the push off the floor
 *   burst 2 (landing)    amplitude 12.03 - 23.50   the body arriving back on it
 * ```
 *
 * Note the raw burst count is higher than 2.0/rep — 26 and 22 for ten reps — because the
 * rebounds below are counted before the guards remove them. It is the *surviving* count
 * that is exactly 20 in both.
 *
 * Landing harder than take-off is not a quirk of these two recordings; it is what falling
 * under gravity onto a stiff surface does. That ordering is the signal this detector pairs
 * on.
 *
 * ## The pipeline
 * ```
 *   frames -> BurstDetector -> reject rebounds -> pair (soft, hard) -> RepEvent
 * ```
 *
 * Burst extraction is not reimplemented here: [BurstDetector] supplies it, which in turn
 * reuses the magnitude, 250 ms time-based filter and 10.15/9.7 Schmitt trigger already
 * validated inside [SquatRepDetector] against four real recordings. Nothing on the squat
 * path is modified or duplicated; see [BurstDetector] for why that is composition rather
 * than an extracted base class.
 *
 * ## The guards
 *
 * ### [minBurstAmplitude] — rejecting rebounds
 * Each real impact is followed by small secondary bursts as the body settles and the phone
 * shifts in the pocket. Replayed against the two fixtures these measure **0.50 - 1.87**
 * peak-to-peak, against **9.05** for the softest genuine impact: a **4.84x** gap with
 * nothing inside it. That is a far more comfortable separation than anything in the squat
 * detector, where the equivalent decision runs on a 0.15-wide band.
 *
 * The default sits at the **geometric** centre of that gap rather than the arithmetic one:
 * `sqrt(1.87 x 9.05) = 4.11`, rounded to **4.1**. Geometric because the quantity is a ratio —
 * doubling a person's effort doubles the impact and the rebound together — so the margin
 * that matters is multiplicative. At 4.1 the cutoff sits **2.19x above** the largest
 * rebound seen and **2.21x below** the softest genuine impact: near-symmetric in the units
 * the signal actually scales in. The arithmetic centre would sit 2.9x above the rebounds
 * but only 1.7x below the real bursts, quietly spending most of the margin on the side that
 * needs it less.
 *
 * ### [minBurstDurationMs] — the secondary rebound check
 * The same rebounds run **51 - 121 ms**, and the shortest genuine impact runs **283 ms**.
 * The default of **130 ms** sits 9 ms above the longest rebound and 153 ms below the
 * shortest real impact. Both bounds are now measured; an earlier version of this note said
 * the upper one was not, which was true only before the fixtures landed.
 *
 * **This guard is redundant on the data that exists.** Replaying both recordings, it
 * rejects nothing [minBurstAmplitude] has not already rejected: all eight discarded bursts
 * across the two traces fall under 1.87, well below the 4.1 floor. It is kept as a cheap
 * second opinion rather than removed, because the two guards fail in different directions
 * and the amplitude floor is the one carrying a single participant's evidence.
 *
 * The clearest illustration of which guard is load-bearing is the first burst of
 * `jumpingjack_10_mohit_paced_fast`: **0.66 amplitude over 877 ms**. Duration would have
 * kept it; amplitude threw it out. A long, weak drift is the failure mode duration cannot
 * see.
 *
 * This mirrors the squat detector exactly, where `minRepDurationMs` also stopped
 * contributing to the wobble/rep decision. Note the direction of the risk is opposite
 * there: on squats the populations crossed over, whereas here duration still separates
 * cleanly (121 vs 283) and simply has nothing left to reject.
 *
 * ### Pairing — why the test is relative, not a fixed boundary
 * An absolute classifier at **12.0** would in fact have worked on both fixtures. It is
 * deliberately **not** used, and replaying them is what turned that from caution into a
 * measured argument:
 *
 * ```
 *   trace                              soft          hard           margin at 12.0
 *   jumpingjack_10_mohit_paced         9.05 - 11.19  12.03 - 19.62  0.03
 *   jumpingjack_10_mohit_paced_fast   10.57 - 11.72  12.35 - 23.50  0.35
 * ```
 *
 * The populations separate — but on the slower recording the nearest landing clears the
 * boundary by **0.03 m/s^2**. That is not a margin; it is a coincidence. And both traces
 * are the same person on the same phone, so a second participant has every chance of
 * closing it.
 *
 * Both recordings are one person on one phone, and the squat detector already paid for
 * exactly this mistake: its `minAmplitude` floor is set by a single participant, and the
 * softest genuine squat across participants varies by **2.8x**. An absolute 12.0 boundary
 * would fail the same way but worse — a lighter user whose landings all fall under 12.0
 * would have every burst classified "soft" and would count **zero** reps, silently.
 *
 * So pairing asks only that the second impact is larger than the first, which is the part
 * of the model that is physics rather than one person's mass. It scales with whoever is
 * jumping, and it held for **20 of 20** pairs across the two recordings.
 *
 * ### Resynchronising
 * Bursts are consumed in order. When a burst is *not* larger than the one being held, the
 * held one cannot have been a jump-out — most likely the recording began mid-rep and it is
 * a landing whose take-off was never seen. It is discarded and the new burst becomes the
 * candidate, rather than forcing a pair that would span two different repetitions.
 *
 * ## This detector assumes a paced cadence
 * **Unpaced jumping jacks are not reliably countable by this method, and that is a property
 * of the movement rather than of the thresholds.** The 2.0 bursts-per-rep ratio the whole
 * pairing model rests on only holds when the user is working to a beat:
 *
 * ```
 *   paced, recording A      2.0 bursts/rep      <- the model holds
 *   paced, recording B      2.0 bursts/rep      <- the model holds
 *   unpaced, ~0.64 s/rep    2.0 bursts/rep
 *   unpaced, ~1 s/rep       1.2 bursts/rep      <- the model breaks
 * ```
 *
 * At a slower, unforced cadence the jump-out stops registering as a separate impact on
 * roughly four reps in five: the movement is gentle enough that the take-off never lifts
 * the smoothed magnitude clear of the trigger, so the rep produces one burst instead of
 * two. No pairing rule can recover a second impact that was never in the signal, and no
 * threshold change fixes it — lowering the trigger far enough to catch those soft take-offs
 * would also admit the rebounds this detector spends two guards rejecting.
 *
 * The consequence for the app is a product requirement, not an implementation detail: a
 * jumping-jack set **must be paced** — a metronome, a visual beat, an audio cue — and the
 * counter should say so rather than under-reporting a set the user performed correctly.
 *
 * ## What is not modelled
 * - **No maximum gap between the paired impacts.** If a rep's landing is missed entirely,
 *   its jump-out can pair with the *next* rep's landing and two reps collapse into one.
 *   A gap limit would catch that, and the fixtures now make one sizable: measured across
 *   both traces, a jump-out is followed by its landing **565-635 ms** later, while
 *   consecutive reps start **1058-1320 ms** apart. Those two populations are cleanly
 *   separated, so a limit near 800 ms would work today. It is still left out, because on
 *   this data it would reject nothing — no landing is ever missed — and an unexercised
 *   guard sized on one participant's cadence is a liability rather than a safeguard,
 *   especially as the whole detector already requires a paced set. Add it when a recording
 *   actually drops a landing.
 * - **No [RepPhase] reporting.** The phases in that enum describe a squat's descent and
 *   ascent. A jumping jack has neither in any sense this detector can observe, so reporting
 *   them would be a lie in the same way the squat detector refuses to report
 *   [RepPhase.BOTTOM]. [awaitingLanding] says what is actually known: whether a jump-out is
 *   waiting for its landing.
 *
 * ## Status of these numbers
 * Both defaults were derived **before** the recordings were in the suite, from reported
 * burst statistics, and then replayed against the fixtures **without being retuned**. They
 * held: 10 of 10 reps on each trace, exactly 2.0 surviving bursts per rep on both, and the
 * measured amplitude gap (1.87 to 9.05) landed within 0.03 of the figure the original
 * arithmetic predicted.
 *
 * What that does **not** establish is generality. The evidence is two recordings of one
 * participant on one phone, taken minutes apart, and amplitude is the most person-dependent
 * quantity in the whole detector — the squat path already demonstrates the cost of a floor
 * set by a single person. A second participant is the obvious next step, and the figure to
 * watch is the softest jump-out: it is what the 4.1 floor sits under.
 *
 * Pure Kotlin, no Android dependencies. Stateful and not thread-safe: one detector per
 * session, frames in chronological order.
 *
 * @param minBurstAmplitude smallest peak-to-peak swing, in m/s^2, that counts as a real
 *   impact rather than a rebound. Default 4.1, the geometric centre of the 1.9-9.0 gap.
 * @param minBurstDurationMs shortest burst that counts as a real impact. Default 130 ms,
 *   10 ms above the longest rebound observed. See the guard note above before raising it.
 * @param burstDetector the burst source. Injectable so a test can drive the pairing logic
 *   with constructed bursts; defaults to the standard trigger and filter.
 */
class JumpingJackRepDetector(
    private val minBurstAmplitude: Float = DEFAULT_MIN_BURST_AMPLITUDE,
    private val minBurstDurationMs: Long = DEFAULT_MIN_BURST_DURATION_MS,
    private val burstDetector: BurstDetector = BurstDetector()
) {

    init {
        require(minBurstAmplitude >= 0f) { "minBurstAmplitude cannot be negative" }
        require(minBurstDurationMs >= 0L) { "minBurstDurationMs cannot be negative" }
    }

    private val _reps = mutableListOf<RepEvent>()

    /** Reps detected so far, in the order they completed. */
    val reps: List<RepEvent> get() = _reps

    /** How many full reps have been counted so far. */
    val repCount: Int get() = _reps.size

    /**
     * A jump-out is being held, waiting for the landing that would complete its rep.
     *
     * This is the honest equivalent of the squat detector's phase readout: the only state
     * this machine has is "holding an impact" or "not".
     */
    val awaitingLanding: Boolean get() = pendingJumpOut != null

    /** The most recent smoothed magnitude, for debugging and threshold work. */
    val smoothedMagnitude: Float get() = burstDetector.smoothedMagnitude

    /** The impact held as a candidate jump-out, or null when none is. */
    private var pendingJumpOut: Burst? = null

    /**
     * Feeds one frame in.
     *
     * @return the completed [RepEvent] if this frame closed the landing of a pair, or null
     *   otherwise — including when a burst closed but was a rebound, or was held as a
     *   jump-out awaiting its landing.
     */
    fun process(frame: MotionFrame): RepEvent? {
        val burst = burstDetector.process(frame) ?: return null
        if (!isRealImpact(burst)) return null // rebound: too small or too brief
        return pair(burst)
    }

    /**
     * Runs a whole trace through [process] and returns every rep completed *by this call*.
     * State carries over, so a detector can be fed in chunks.
     */
    fun processAll(frames: List<MotionFrame>): List<RepEvent> = frames.mapNotNull { process(it) }

    /** Clears detected reps, drops any held jump-out, and empties the filter. */
    fun reset() {
        _reps.clear()
        pendingJumpOut = null
        burstDetector.reset()
    }

    /** True when a burst is large enough and long enough to be an impact rather than a rebound. */
    private fun isRealImpact(burst: Burst): Boolean =
        burst.amplitude >= minBurstAmplitude && burst.durationMs >= minBurstDurationMs

    /**
     * Offers one surviving impact to the pairing state machine.
     *
     * @return the rep this impact completed, or null if it was held as a jump-out or used
     *   to resynchronise.
     */
    private fun pair(burst: Burst): RepEvent? {
        val held = pendingJumpOut
        if (held == null) {
            pendingJumpOut = burst
            return null
        }

        if (burst.amplitude <= held.amplitude) {
            // Not a landing: a landing is harder than its own take-off. The held burst was
            // most likely a landing whose jump-out was never recorded, so drop it and treat
            // this one as the new candidate rather than pairing across two repetitions.
            pendingJumpOut = burst
            return null
        }

        pendingJumpOut = null
        val event = RepEvent(
            index = _reps.size,
            startMs = held.startMs,
            endMs = burst.endMs,
            // The landing's swing, not the pair's sum: it is the larger and more repeatable
            // of the two, and it is what "how hard was this rep" means for a jumping jack.
            amplitude = burst.amplitude
        )
        _reps += event
        return event
    }

    companion object {
        /** Geometric centre of the 1.9 (largest rebound) to 9.0 (softest impact) gap. */
        const val DEFAULT_MIN_BURST_AMPLITUDE = 4.1f

        /** 10 ms above the longest rebound observed (120 ms). */
        const val DEFAULT_MIN_BURST_DURATION_MS = 130L

        /**
         * The soft/hard boundary the reference recordings imply, recorded for reference and
         * **deliberately not used** — see the pairing note in this class's documentation.
         * Kept as a constant so a future calibration step has the observed figure to start
         * from rather than rediscovering it.
         */
        const val OBSERVED_SOFT_HARD_BOUNDARY = 12.0f

        /** Bursts per rep in both paced reference recordings. */
        const val PACED_BURSTS_PER_REP = 2
    }
}
