package com.repmate.engine
import com.repmate.data.repo.SessionRepository
/**
 * One sample of the detector's smoothed motion signal, paired with when it occurred.
 *
 * [SquatRepDetector.smoothedMagnitude] only exposes the single latest value (see its own
 * KDoc: "exposed for debugging and threshold tuning"), not a stored history. This is what
 * [SessionReplayer] captures itself, frame by frame, so a rep's motion curve can be drawn
 * after the fact -- without needing any change to RepDetector.kt.
 */
data class SmoothedSample(
    val tMillis: Long,
    val magnitude: Float
)

/**
 * Everything the Motion Replay screen needs for one rep: the raw detection, its score, and
 * the smoothed-motion curve across that rep's time window (for the "your curve vs calibration
 * range" chart agreed with Henrico -- see the project tracker, section 19.1).
 */
data class ReplayedRep(
    val event: RepEvent,
    val score: RepScore,
    val curve: List<SmoothedSample>
)

/** The result of replaying one whole session: every rep, in order, ready for the UI. */
data class ReplayedSession(
    val session: WorkoutSession,
    val reps: List<ReplayedRep>
)

/**
 * Re-runs a previously recorded [WorkoutSession]'s raw [MotionFrame]s back through the same
 * detector + scorer pipeline used live, so the Motion Replay screen (and Jasper's developer
 * mode, and JUnit tests) can show each rep's score and motion curve after the fact.
 *
 * ## Why re-run detection instead of trusting WorkoutSession.reps
 * [WorkoutSession.reps] only carries the already-graded [RepScore]s -- neither the raw
 * [RepEvent] time window nor a motion curve survives into that. Re-running the same frames
 * through [SquatRepDetector] is deterministic (same detector, same profile, same frames in,
 * same reps out), so it reproduces the original [RepEvent]s exactly while additionally
 * letting this class capture the smoothed signal per frame -- something [SquatRepDetector]
 * does not store on its own.
 *
 * ## Why frame-by-frame instead of processAll
 * [SquatRepDetector.processAll] is a one-line convenience
 * (`frames.mapNotNull { process(it) }`) that only returns completed [RepEvent]s. Calling
 * [SquatRepDetector.process] frame-by-frame here instead, and reading
 * [SquatRepDetector.smoothedMagnitude] after every call, gets the exact same detection result
 * while additionally recording the curve -- no change to RepDetector.kt needed.
 *
 * ## Frames are required
 * [WorkoutSession.frames] is nullable -- see [SessionRepository]'s own KDoc, which flags that
 * a real storage implementation may choose to drop frames rather than persist tens of
 * thousands of samples per session. [replay] returns `null` when frames are absent, rather
 * than throwing -- degrade gracefully (Golden Rule 7), the same pattern as
 * [RepScore.pauseSeconds] always being `0f` and [RepScore.rangePercent] using a sentinel for
 * uncalibrated users. The caller should treat a `null` result as "no replay data for this
 * session" and fall back to showing [WorkoutSession.reps] without a curve.
 *
 * Pure Kotlin, no Android dependencies -- same shape as [FormScorer]/[SquatRepDetector], so it
 * can be unit tested against [syntheticSquatTrace] the same way FormScorerTest is today.
 */
class SessionReplayer(
    private val formScorer: FormScorer = FormScorer()
) {

    /**
     * Replays [session] and returns every rep with its score and motion curve, or `null` if
     * [WorkoutSession.frames] is absent.
     *
     * @param profile the same calibration profile the live workout used, so the detector's
     *   thresholds and FormScorer's reference amplitude match what was used originally. Pass
     *   `null` to replay as if uncalibrated (matches [FormScorer.score]'s own null-profile path).
     */
    fun replay(session: WorkoutSession, profile: CalibrationProfile?): ReplayedSession? {
        val frames = session.frames ?: return null

        val detector = SquatRepDetector(profile)
        val allSamples = mutableListOf<SmoothedSample>()
        val events = mutableListOf<RepEvent>()

        for (frame in frames) {
            val completedRep = detector.process(frame)
            allSamples += SmoothedSample(frame.tMillis, detector.smoothedMagnitude)
            if (completedRep != null) events += completedRep
        }

        val replayedReps = events.mapIndexed { i, event ->
            ReplayedRep(
                event = event,
                score = formScorer.score(event, profile, previousReps = events.subList(0, i)),
                // Slice by the rep's own window, not "everything since the last completed
                // rep" -- that would fold the idle gap before this rep started into its curve.
                curve = allSamples.filter { it.tMillis in event.startMs..event.endMs }
            )
        }

        return ReplayedSession(session, replayedReps)
    }
}