package com.repmate.pose

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The geometry behind the push-up camera workout, kept free of ML Kit and Android types so it
 * runs in a plain JVM unit test -- the same split [com.repmate.engine] keeps between "pure logic"
 * and "the platform that feeds it". Nothing in `com.repmate.engine` is touched or depended on here;
 * the boundary is [PushupPoseAnalyzer][com.repmate.ui.workout.pushup.PushupPoseAnalyzer], which
 * turns ML Kit's `Pose` into the plain types below, then the app layer turns one [Arm]'s angle into
 * the `Double?` stream [com.repmate.engine.PushupRepDetector] consumes.
 */

/**
 * One body landmark from ML Kit's `position3D`.
 *
 * @property x horizontal position in pixels of the upright analysed frame.
 * @property y vertical position in pixels; y grows downward.
 * @property z ML Kit's depth estimate, on roughly the same scale as x, with the midpoint of the
 *   hips at 0. **Smaller is nearer the camera.** Not used for the angle itself (see
 *   [elbowAngleDegrees]) -- only for [ArmLock] to judge which arm is nearer, and so more reliably
 *   tracked, than the other.
 * @property likelihood ML Kit's `inFrameLikelihood`, 0..1. It says whether the point is **inside
 *   the picture**, not whether the camera can actually see it -- a hidden arm can still score near
 *   1.0, which is why [ArmLock] locks to one arm once and does not re-pick every frame.
 */
data class Joint(val x: Float, val y: Float, val z: Float, val likelihood: Float)

/**
 * Shoulder, elbow and wrist of one arm.
 *
 * @property side which arm, as the pose detector labels it.
 */
data class Arm(
    val side: Side,
    val shoulder: Joint,
    val elbow: Joint,
    val wrist: Joint,
) {
    enum class Side { LEFT, RIGHT }

    /**
     * The **weakest** of the three likelihoods, not their average: an angle needs all three
     * points, and one out-of-frame wrist makes the whole angle a guess.
     */
    val confidence: Float get() = minOf(shoulder.likelihood, elbow.likelihood, wrist.likelihood)

    /** Mean depth of the three points. Smaller means the arm is nearer the camera. */
    val meanZ: Float get() = (shoulder.z + elbow.z + wrist.z) / 3f
}

/** What the workout screen knows about whether it is seeing the user at all. */
enum class Tracking {
    /** No person in frame: the pose detector returned no landmarks. */
    NO_PERSON,

    /** A person, but the locked arm has a point likely outside the frame. */
    LOW_CONFIDENCE,

    /** A person, with every point of the locked arm likely inside the frame. */
    TRACKING,
}

/** Segments shorter than this (pixels) have no meaningful direction. */
private const val MIN_SEGMENT_PX = 1.0

/**
 * Interior elbow angle from the **2D image** position, 0 (folded) to 180 (straight).
 *
 * Only faithful when the arm bends in a plane facing the camera -- i.e. the camera is side-on,
 * which is exactly the placement the workout screen asks for ("phone propped to the side, whole
 * body in frame"). With the phone in *front* of the user instead, the elbow bends towards and away
 * from the lens, which a flat image cannot show; that placement is out of scope here.
 *
 * Computed as the difference of the two segment directions folded into 0..180, rather than `acos`
 * of a dot product, which loses precision near 0 and 180.
 *
 * @return the angle, or null when either segment is shorter than [MIN_SEGMENT_PX].
 */
fun elbowAngleDegrees(
    shoulderX: Float, shoulderY: Float,
    elbowX: Float, elbowY: Float,
    wristX: Float, wristY: Float,
): Double? {
    val upperX = (shoulderX - elbowX).toDouble()
    val upperY = (shoulderY - elbowY).toDouble()
    val foreX = (wristX - elbowX).toDouble()
    val foreY = (wristY - elbowY).toDouble()
    if (hypot(upperX, upperY) < MIN_SEGMENT_PX || hypot(foreX, foreY) < MIN_SEGMENT_PX) return null

    var degrees = abs(atan2(upperY, upperX) - atan2(foreY, foreX)) * 180.0 / PI
    if (degrees > 180.0) degrees = 360.0 - degrees
    return degrees
}

/** [elbowAngleDegrees] for an [Arm]. */
fun elbowAngleDegrees(arm: Arm): Double? = elbowAngleDegrees(
    arm.shoulder.x, arm.shoulder.y, arm.elbow.x, arm.elbow.y, arm.wrist.x, arm.wrist.y,
)

/** How far below the entry threshold an established tracking label may sink before it is dropped. */
const val EXIT_CONFIDENCE_RATIO = 0.6f

/**
 * Classifies a frame for the tracking indicator.
 *
 * ## Hysteresis
 * A confidence hovering near a single gate makes a naive label flip several times a second, which
 * says nothing about the user and everything about where the number sits relative to the line.
 * Like [com.repmate.engine.PushupRepDetector]'s Schmitt trigger, the label has two levels: it
 * takes the full [minConfidence] to *become* TRACKING, and it stays TRACKING until confidence
 * falls below [exitConfidence].
 *
 * @param minConfidence threshold on [Arm.confidence] to start tracking.
 * @param previous the label on the previous frame, or null on the first.
 * @param exitConfidence the lower threshold below which an established TRACKING is dropped.
 */
fun trackingState(
    arm: Arm?,
    minConfidence: Float,
    previous: Tracking? = null,
    exitConfidence: Float = minConfidence * EXIT_CONFIDENCE_RATIO,
): Tracking = when {
    arm == null -> Tracking.NO_PERSON
    arm.confidence >= minConfidence -> Tracking.TRACKING
    previous == Tracking.TRACKING && arm.confidence >= exitConfidence -> Tracking.TRACKING
    else -> Tracking.LOW_CONFIDENCE
}
