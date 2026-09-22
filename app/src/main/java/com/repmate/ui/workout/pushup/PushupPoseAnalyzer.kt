package com.repmate.ui.workout.pushup

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.repmate.pose.Arm
import com.repmate.pose.Joint

/**
 * Runs ML Kit pose detection on the camera stream and turns each result into the two [Arm]s
 * [com.repmate.pose] and [com.repmate.engine.PushupRepDetector] work with -- the one place ML
 * Kit's types are allowed to exist in the push-up workout.
 *
 * `STREAM_MODE` (not `SINGLE_IMAGE_MODE`): it uses the previous frame's pose to track the next
 * instead of starting afresh each time, which matters for a continuous camera feed like this.
 *
 * @param onPose called on the main thread with this frame's left/right arm (either may be null)
 *   once detection finishes. Never called concurrently -- CameraX's `STRATEGY_KEEP_ONLY_LATEST`
 *   plus closing each frame only after its own detection completes means at most one frame is
 *   in flight at a time.
 */
class PushupPoseAnalyzer(
    private val onPose: (left: Arm?, right: Arm?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val poseDetector: PoseDetector =
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build(),
        )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        poseDetector.process(input)
            .addOnSuccessListener { pose -> onPose(armOf(pose, Arm.Side.LEFT), armOf(pose, Arm.Side.RIGHT)) }
            .addOnCompleteListener { image.close() }
    }

    /** Releases the pose detector. Call once, when the camera is unbound. */
    fun close() {
        poseDetector.close()
    }

    private fun armOf(pose: Pose, side: Arm.Side): Arm? {
        val (shoulderType, elbowType, wristType) =
            when (side) {
                Arm.Side.LEFT -> Triple(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
                Arm.Side.RIGHT -> Triple(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
            }
        val shoulder = pose.getPoseLandmark(shoulderType) ?: return null
        val elbow = pose.getPoseLandmark(elbowType) ?: return null
        val wrist = pose.getPoseLandmark(wristType) ?: return null
        return Arm(side, joint(shoulder), joint(elbow), joint(wrist))
    }

    private fun joint(landmark: PoseLandmark): Joint {
        val p = landmark.position3D
        return Joint(p.x, p.y, p.z, landmark.inFrameLikelihood)
    }
}
