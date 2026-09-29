package com.repmate.pose

/**
 * Whether the push-up workout is counting right now, and if not, why. One value so the screen's
 * hint and the decision to feed the rep detector can never disagree.
 */
enum class CountingStatus {
    /** Phone still, a person in frame, an arm locked and clearly visible: frames reach the detector. */
    COUNTING,

    /** The phone is being moved, so the picture cannot be trusted. Takes priority over the rest. */
    PHONE_MOVING,

    /** The pose detector found nobody. */
    NO_PERSON,

    /** Somebody is there, but the arm being read is likely partly out of frame. */
    LOW_CONFIDENCE,

    /** A person is visible and the phone still, but no arm has been chosen yet. */
    FINDING_ARM,
    ;

    val countsReps: Boolean get() = this == COUNTING
}

/**
 * The single rule for [CountingStatus]. Order matters: a moving phone is reported first because it
 * is the one thing the user can fix immediately, and everything the pose detector says about a
 * shaking picture is unreliable anyway.
 */
fun countingStatus(phoneStable: Boolean, tracking: Tracking, armLocked: Boolean): CountingStatus = when {
    !phoneStable -> CountingStatus.PHONE_MOVING
    tracking == Tracking.NO_PERSON -> CountingStatus.NO_PERSON
    tracking == Tracking.LOW_CONFIDENCE -> CountingStatus.LOW_CONFIDENCE
    !armLocked -> CountingStatus.FINDING_ARM
    else -> CountingStatus.COUNTING
}

/**
 * The tracking label for one frame, judging the arm that is (or is about to be) read.
 *
 * - Neither arm found: [Tracking.NO_PERSON].
 * - An arm is locked: judge **that** arm. If it is missing while the other is present, that is
 *   [Tracking.LOW_CONFIDENCE], not "no person" -- somebody is there, the arm in use is not.
 * - Not locked yet: judge the more confident of the two, so the label reads sensibly while the
 *   lock is still being chosen (judging only the locked arm made it read NO_PERSON until then).
 *
 * The hysteresis in [trackingState] applies, so a confidence hovering near [minConfidence] does not
 * flip the label every frame.
 */
fun frameTracking(
    left: Arm?,
    right: Arm?,
    lockedSide: Arm.Side?,
    minConfidence: Float,
    previous: Tracking?,
): Tracking {
    if (left == null && right == null) return Tracking.NO_PERSON
    val arm =
        when (lockedSide) {
            Arm.Side.LEFT -> left
            Arm.Side.RIGHT -> right
            null -> listOfNotNull(left, right).maxByOrNull { it.confidence }
        } ?: return Tracking.LOW_CONFIDENCE
    return trackingState(arm, minConfidence, previous)
}
