package com.repmate.ui.workout.pushup

import com.repmate.engine.PushupRepDetector
import com.repmate.pose.Arm
import com.repmate.pose.ArmLock
import com.repmate.pose.CountingStatus
import com.repmate.pose.Tracking
import com.repmate.pose.countingStatus
import com.repmate.pose.elbowAngleDegrees
import com.repmate.pose.frameTracking

/**
 * Turns one camera frame's arms, plus whether the phone is steady, into either a counted rep or a
 * reason nothing was counted. Pure Kotlin with no Android or ML Kit types, so the gating -- the
 * part most likely to go wrong -- runs in a JVM unit test.
 *
 * It **feeds** [PushupRepDetector] and does not change it: the detector in `com.repmate.engine`
 * still sees only elbow angles. Everything about *whether* to show it an angle lives here.
 *
 * ## The rules, in order
 * 1. **Lock evidence needs a steady phone and a confident person.** The [ArmLock] chooses an arm
 *    from depth readings over the first frames; readings taken while the phone shakes or the person
 *    is half out of frame would pick the wrong one for the whole set.
 * 2. **[countingStatus] decides.** Only [CountingStatus.COUNTING] passes an angle to the detector.
 *    Every other frame is simply not shown to it, so a rep in progress is neither advanced nor lost.
 * 3. **A long block starts over.** If counting was blocked for more than [staleAfterMs], the lock
 *    and the detector are reset when it ends. After a few seconds the user may have walked away or
 *    the phone been moved to a new spot, and resuming the old half-finished rep (or the old arm)
 *    could count something that was never a rep. A short block -- a bump that pauses counting for a
 *    second -- keeps both, so a rep that straddles it is still counted.
 *
 * The rep count itself is not kept here: the detector's count is zeroed by every reset above, and
 * the workout must not lose reps to them. [Result.repCompleted] says a rep just finished; the caller
 * owns the running total.
 *
 * @param minConfidence the weakest of shoulder, elbow and wrist likelihoods needed to start being
 *   tracked; see [com.repmate.pose.trackingState] for the lower level at which tracking is dropped.
 * @param staleAfterMs how long counting may be blocked before the lock and detector start over.
 */
class PushupFrameProcessor(
    private val armLock: ArmLock = ArmLock(),
    private val repDetector: PushupRepDetector = PushupRepDetector(),
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    /**
     * What one frame produced.
     *
     * @property repCompleted true when this frame finished a rep.
     */
    data class Result(
        val status: CountingStatus,
        val tracking: Tracking,
        val armLocked: Boolean,
        val phase: PushupRepDetector.Phase,
        val repCompleted: Boolean,
        val repBottomDegrees: Double? = null,
        /**
         * The detector's smoothed elbow angle after this frame, or null before it has a usable
         * one. Reported so the finished set can be charted (see [PushupAngleTrace]); the smoothed
         * value rather than the raw one, so a single mislabelled landmark is already filtered out.
         */
        val smoothedDegrees: Double? = null,
    )

    private var tracking: Tracking = Tracking.NO_PERSON
    private var blockedSinceMs: Long? = null

    /**
     * @param left the left arm, or null if the pose detector did not find it.
     * @param right the right arm, likewise.
     * @param phoneStable [com.repmate.sensors.PhoneStabilityGate.isStable] at this moment.
     * @param nowMs a clock in milliseconds that only moves forward; only differences are used.
     */
    fun process(left: Arm?, right: Arm?, phoneStable: Boolean, nowMs: Long): Result {
        var newTracking = frameTracking(left, right, armLock.side, minConfidence, tracking)
        if (phoneStable && newTracking == Tracking.TRACKING) {
            armLock.observe(left, right)
            // The lock may have just been chosen, which changes which arm the label judges.
            newTracking = frameTracking(left, right, armLock.side, minConfidence, tracking)
        }
        tracking = newTracking

        var status = countingStatus(phoneStable, tracking, armLock.isLocked)
        val blocked =
            status == CountingStatus.PHONE_MOVING ||
                status == CountingStatus.NO_PERSON ||
                status == CountingStatus.LOW_CONFIDENCE
        if (blocked) {
            if (blockedSinceMs == null) blockedSinceMs = nowMs
        } else {
            val since = blockedSinceMs
            blockedSinceMs = null
            if (since != null && nowMs - since > staleAfterMs) {
                armLock.reset()
                repDetector.reset()
                status = countingStatus(phoneStable, tracking, armLock.isLocked)
            }
        }

        var repCompleted = false
        var repBottomDegrees: Double? = null
        if (status.countsReps) {
            val angle = armLock.armFor(left, right)?.let { elbowAngleDegrees(it) }
            val transition = repDetector.update(angle)
            repCompleted = transition?.repCompleted == true
            repBottomDegrees = transition?.bottomDegrees
        }
        return Result(
            status,
            tracking,
            armLock.isLocked,
            repDetector.phase,
            repCompleted,
            repBottomDegrees,
            repDetector.smoothedDegrees,
        )
    }

    /** Forgets the arm lock and the half-finished rep, e.g. after the camera is switched. */
    fun reset() {
        armLock.reset()
        repDetector.reset()
        tracking = Tracking.NO_PERSON
        blockedSinceMs = null
    }

    companion object {
        /**
         * Enter-tracking threshold on the weakest of shoulder/elbow/wrist likelihood. Kept at 0.5
         * rather than raised because the one real recorded run has a bimodal confidence: about 90%
         * of frames with a person read 0.5 or more, and a rep's bent bottom reads a median 0.88
         * with a tail below 0.8, so a stricter line would drop the frames the bottom of a rep needs.
         */
        const val DEFAULT_MIN_CONFIDENCE = 0.5f

        const val DEFAULT_STALE_AFTER_MS = 3_000L
    }
}
