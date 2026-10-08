package com.repmate.ui.workout.pushup

import com.repmate.data.memory.PushupRepTrace
import com.repmate.engine.PushupRepDetector

/**
 * Collects the elbow-angle curve of each counted push-up, so a finished set can be reported the
 * way a squat set is.
 *
 * ## Why push-ups need their own trace
 * The IMU replay works by keeping the `MotionFrame`s of a set and running the detector over them
 * again. That is no use here: push-ups are counted from the camera, and the accelerometer frames
 * recorded alongside describe a phone propped up and deliberately held still -- there is no
 * movement in them. The movement is the **elbow angle**, which is not a `MotionFrame` and never
 * enters the engine's frame pipeline, so it has to be collected as it goes.
 *
 * ## What a rep's window is
 * [PushupRepDetector] counts a rep as straight -> bent -> straight, so a rep runs from the frame
 * where the arm first reads as bent ([PushupRepDetector.Phase.DOWN]) to the frame that completes
 * it. Samples before the first rep, between reps, and after the last one are dropped: they are
 * the user setting up or resting, not part of any rep.
 *
 * Angles are the detector's **smoothed** value, not the raw one, so the curve is the signal the
 * detector actually decided on -- a single mislabelled landmark is already filtered out of it.
 *
 * Pure Kotlin and driven entirely by what it is handed, so it runs in a plain JVM test: the
 * timestamps come from the caller, which is also what keeps it clear of `SystemClock`.
 *
 * @param maxSamplesPerRep an upper bound per rep, so a rep left open by someone who walks away
 *   mid-set cannot grow without limit. At ~30 camera frames a second, 1,800 is a minute.
 */
class PushupAngleTrace(
    private val maxSamplesPerRep: Int = DEFAULT_MAX_SAMPLES_PER_REP,
) {
    private val completed = mutableListOf<PushupRepTrace>()
    private val current = mutableListOf<Pair<Long, Float>>()
    private var repStartMs: Long? = null
    private var lastPhase: PushupRepDetector.Phase = PushupRepDetector.Phase.WAITING

    /** The reps counted so far, oldest first. */
    val reps: List<PushupRepTrace> get() = completed.toList()

    /**
     * Feeds one processed camera frame in.
     *
     * @param nowMs the same forward-only clock [PushupFrameProcessor.process] was given.
     * @param phase the detector's phase after this frame.
     * @param smoothedDegrees the detector's smoothed elbow angle, or null when this frame had no
     *   usable angle (nobody in frame, the locked arm hidden, counting blocked).
     * @param repCompleted whether this frame finished a rep.
     * @param bottomDegrees the finished rep's lowest angle, when [repCompleted].
     */
    fun onFrame(
        nowMs: Long,
        phase: PushupRepDetector.Phase,
        smoothedDegrees: Double?,
        repCompleted: Boolean,
        bottomDegrees: Double?,
    ) {
        val enteringDown = phase == PushupRepDetector.Phase.DOWN && lastPhase != PushupRepDetector.Phase.DOWN
        lastPhase = phase

        if (enteringDown) {
            // A new rep starts at the frame the arm first reads as bent.
            current.clear()
            repStartMs = nowMs
        }

        val startedAt = repStartMs
        if (startedAt != null && smoothedDegrees != null && current.size < maxSamplesPerRep) {
            current += (nowMs - startedAt) to smoothedDegrees.toFloat()
        }

        if (repCompleted) {
            // Closed even if no samples were usable: a rep the user did is a rep, and the report
            // shows its score either way -- it just has no curve to draw.
            completed +=
                PushupRepTrace(
                    repIndex = completed.size + 1,
                    curve = current.toList(),
                    bottomDegrees = bottomDegrees,
                )
            current.clear()
            repStartMs = null
        }
    }

    /** Back to no reps, for a reset or a new set. */
    fun reset() {
        completed.clear()
        current.clear()
        repStartMs = null
        lastPhase = PushupRepDetector.Phase.WAITING
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES_PER_REP = 1_800
    }
}
