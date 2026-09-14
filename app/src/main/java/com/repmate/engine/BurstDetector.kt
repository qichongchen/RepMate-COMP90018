package com.repmate.engine

/**
 * One movement burst: a span during which the smoothed acceleration magnitude stayed above
 * the trigger's release level.
 *
 * A burst is **not** a rep. A squat is one burst; a jumping jack is two (jump-out, then
 * landing); a phone going into a pocket is also one. What a burst means is the detector's
 * job to decide, which is why this type is deliberately separate from [RepEvent] — handing
 * back a `RepEvent` here would invite treating an unpaired impact as a repetition.
 *
 * @property startMs timestamp of the frame that crossed the high threshold.
 * @property endMs timestamp of the frame that fell back under the low threshold.
 * @property amplitude peak-to-peak swing of the **smoothed** magnitude across the burst.
 */
data class Burst(
    val startMs: Long,
    val endMs: Long,
    val amplitude: Float
) {
    /** How long the burst lasted, in milliseconds. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * Emits every raw movement [Burst] in a stream, applying no opinion about what a rep is.
 *
 * ## Why this delegates to [SquatRepDetector] instead of reimplementing it
 * The machinery a burst needs — rotation-invariant magnitude, the 250 ms time-based moving
 * average, and the 10.15/9.7 Schmitt trigger — already exists inside [SquatRepDetector],
 * where it is covered by the replay suite against four real recordings. It is also
 * genuinely exercise-agnostic: nothing in it knows what a squat is. Only the *guards*
 * layered on top (minimum duration, minimum amplitude, cooldown) encode "this burst was a
 * squat".
 *
 * So this class holds a [SquatRepDetector] with those guards opened all the way up, and
 * every window the trigger opens comes straight back out. `minRepDurationMs` is 1 ms (the
 * smallest the constructor permits), `minAmplitude` is 0, and `cooldownMs` is 0 — none of
 * them can reject anything. What survives is exactly the trigger's raw output.
 *
 * This is composition rather than an extracted base class **on purpose**. Pulling the
 * filter and trigger out into a shared superclass would mean editing [SquatRepDetector],
 * whose thresholds and behaviour are pinned by replay tests against four participants; that
 * edit is not worth making for a second exercise that has no fixtures yet. The cost is that
 * a class named for squats appears inside the jumping-jack path, which reads oddly. If the
 * squat path is ever opened for refactoring anyway, the right move is to lift the filter and
 * trigger into this class properly and have [SquatRepDetector] delegate *to it* — the
 * dependency should eventually point the other way.
 *
 * ## What is inherited deliberately
 * `maxRepDurationMs` is **not** disabled. A window that never closes — the phone parked at
 * ~9.81, between the two thresholds — is abandoned mid-flight and the trigger re-arms only
 * once the signal drops back under the low threshold. That protection is worth keeping at
 * the burst layer, and rebuilding it here would be the duplication this class exists to
 * avoid. Its default (3000 ms) is far above any impact: a jumping jack's landing spike is a
 * few hundred milliseconds at most.
 *
 * Pure Kotlin, no Android dependencies. Stateful and not thread-safe: one instance per
 * session, frames fed in chronological order.
 *
 * @param highThreshold m/s^2 the smoothed magnitude must exceed to open a burst.
 * @param lowThreshold m/s^2 it must fall under to close one.
 * @param smoothingWindowMs length of the moving-average filter, in milliseconds.
 * @param maxBurstDurationMs how long a burst may stay open before it is abandoned unread.
 */
class BurstDetector(
    highThreshold: Float = SquatRepDetector.DEFAULT_HIGH_THRESHOLD,
    lowThreshold: Float = SquatRepDetector.DEFAULT_LOW_THRESHOLD,
    smoothingWindowMs: Long = SquatRepDetector.DEFAULT_SMOOTHING_WINDOW_MS,
    maxBurstDurationMs: Long = SquatRepDetector.DEFAULT_MAX_REP_DURATION_MS
) {

    private val windows = SquatRepDetector(
        highThreshold = highThreshold,
        lowThreshold = lowThreshold,
        // The three guards that encode "this burst was a squat", switched off. 1 ms is the
        // smallest minRepDurationMs the constructor accepts, and no burst can be shorter
        // than one sample interval anyway, so nothing is filtered here.
        minRepDurationMs = 1L,
        maxRepDurationMs = maxBurstDurationMs,
        cooldownMs = 0L,
        minAmplitude = 0f,
        smoothingWindowMs = smoothingWindowMs
    )

    /** The most recent smoothed magnitude, exposed for debugging and threshold work. */
    val smoothedMagnitude: Float get() = windows.smoothedMagnitude

    /** How many samples the most recent average was taken over. */
    val smoothingSampleCount: Int get() = windows.smoothingSampleCount

    /** Frame rate implied by the timestamps inside the filter window, in Hz. */
    val observedSampleRateHz: Float get() = windows.observedSampleRateHz

    /**
     * Feeds one frame in.
     *
     * @return the [Burst] that closed on this frame, or null if none did.
     */
    fun process(frame: MotionFrame): Burst? =
        windows.process(frame)?.let { Burst(it.startMs, it.endMs, it.amplitude) }

    /** Runs a whole trace through [process], returning every burst it closed. */
    fun processAll(frames: List<MotionFrame>): List<Burst> = frames.mapNotNull { process(it) }

    /** Empties the filter and returns the trigger to its resting state. */
    fun reset() = windows.reset()
}
