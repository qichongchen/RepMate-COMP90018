package com.repmate.ui.workout.pushup

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.PushupRepDetector
import com.repmate.pose.Arm
import com.repmate.pose.Tracking
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.theme.RepMateTheme
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The push-up workout screen: camera preview, on-device pose detection, and real rep counting,
 * end to end. See [PushupWorkoutViewModel]'s KDoc for how a frame becomes a counted rep.
 *
 * Split into this stateful wrapper (camera + permission, which need real Android/CameraX types)
 * and the stateless [PushupWorkoutContent] below, same reasoning as every other screen in this
 * app -- previews render from a plain [PushupWorkoutUiState], no camera or Hilt required.
 *
 * ## The screen faces away
 * The phone is propped to the side with the rear camera, so the user is not looking at this
 * screen mid-set -- every counted rep is also spoken and buzzed (see `RepFeedback`, wired in
 * [PushupWorkoutViewModel]), the same reasoning [com.repmate.ui.workout.LiveWorkoutViewModel]
 * uses for every exercise, just more load-bearing here since there is no glance-at-the-phone
 * fallback.
 *
 * @param onWorkoutFinished invoked once, with the session's id, once "End workout" has actually
 *   saved it -- the caller decides what route that leads to (Motion Replay), same contract as
 *   `LiveWorkoutScreen`.
 */
@Composable
fun PushupWorkoutScreen(
    onWorkoutFinished: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PushupWorkoutViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inPreview = LocalInspectionMode.current

    var hasPermission by remember {
        mutableStateOf(
            inPreview || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }

    LaunchedEffect(viewModel) {
        viewModel.workoutFinished.collect { sessionId -> onWorkoutFinished(sessionId) }
    }

    // The set can run for minutes with the phone propped up and no touches -- must not sleep and
    // lose the camera mid-set.
    KeepScreenOnEffect()

    if (hasPermission) {
        val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        CameraEffect(previewView = previewView, lifecycleOwner = lifecycleOwner, onPose = viewModel::onPose)

        PushupWorkoutContent(
            uiState = uiState,
            previewView = if (inPreview) null else previewView,
            onEndWorkoutClicked = viewModel::onEndWorkoutClicked,
            modifier = modifier,
        )
    } else {
        CameraPermissionRationale(
            onGrantClicked = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            modifier = modifier,
        )
    }
}

/** Sets `FLAG_KEEP_SCREEN_ON` for as long as this composable is present, then clears it. */
@Composable
private fun KeepScreenOnEffect() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Binds the camera + pose analyzer to [lifecycleOwner]'s lifecycle, unbinding on dispose. */
@Composable
private fun CameraEffect(
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    onPose: (Arm?, Arm?) -> Unit,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current

    DisposableEffect(lifecycleOwner) {
        if (inPreview) return@DisposableEffect onDispose {}

        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analyzer = PushupPoseAnalyzer(onPose = onPose)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                val preview = CameraPreview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // KEEP_ONLY_LATEST: if pose detection is slower than the camera, frames are
                // dropped rather than queued, so what is analysed is always about now.
                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                try {
                    provider.unbindAll()
                    // Rear camera: the screen faces away from the user (see this file's KDoc).
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    Log.e(TAG, "could not bind the camera", e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }
}

private const val TAG = "RepMatePushupWorkout"

@Composable
private fun CameraPermissionRationale(
    onGrantClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera access needed",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "RepMate counts push-ups by watching your arm through the camera -- " +
                "nothing is recorded or leaves your phone. Grant camera access to start.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        RepMateButton(text = "Grant camera access", onClick = onGrantClicked)
    }
}

@Composable
private fun PushupWorkoutContent(
    uiState: PushupWorkoutUiState,
    previewView: PreviewView?,
    onEndWorkoutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (previewView != null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            // Design-time / preview only: no camera in an @Preview.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // A translucent scrim under the overlay text keeps it legible over whatever the camera
        // sees, in both themes, without needing the screen to know anything about the preview's
        // actual content.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .safeDrawingPadding()
                    .padding(24.dp),
        ) {
            TrackingBadge(tracking = uiState.tracking, armLocked = uiState.armLocked)

            if (!uiState.armLocked) {
                Spacer(modifier = Modifier.height(16.dp))
                PlacementInstructions()
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = uiState.repCount.toString(),
                    style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
                Text(
                    text = "REPS",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "phase: ${uiState.phase.name.lowercase(Locale.US)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    FeedbackIndicator(icon = Icons.Filled.Vibration, label = "buzz on rep")
                    FeedbackIndicator(icon = Icons.Filled.RecordVoiceOver, label = "beep count")
                }
            }

            RepMateButton(
                text = "End workout",
                onClick = onEndWorkoutClicked,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrackingBadge(
    tracking: Tracking,
    armLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val (color, label) =
        when {
            tracking == Tracking.NO_PERSON -> Color(0xFFE53935) to "no person in frame"
            tracking == Tracking.LOW_CONFIDENCE -> Color(0xFFFFB300) to "low confidence"
            !armLocked -> Color(0xFFFFB300) to "finding your arm..."
            else -> Color(0xFF43A047) to "tracking"
        }
    Row(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun PlacementInstructions(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                .padding(16.dp),
    ) {
        Text(
            text = "Prop your phone to the side, at floor level, so your whole body is in frame.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FeedbackIndicator(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

private val PREVIEW_STATE =
    PushupWorkoutUiState(
        repCount = 6,
        phase = PushupRepDetector.Phase.UP,
        tracking = Tracking.TRACKING,
        armLocked = true,
        elapsedMillis = 42_000L,
    )

@Preview(name = "Push-up workout - tracking", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutTrackingPreview() {
    RepMateTheme {
        PushupWorkoutContent(uiState = PREVIEW_STATE, previewView = null, onEndWorkoutClicked = {})
    }
}

@Preview(name = "Push-up workout - finding arm", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutFindingArmPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PushupWorkoutUiState(tracking = Tracking.TRACKING, armLocked = false),
            previewView = null,
            onEndWorkoutClicked = {},
        )
    }
}

@Preview(name = "Push-up workout - permission rationale", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutPermissionRationalePreview() {
    RepMateTheme {
        CameraPermissionRationale(onGrantClicked = {})
    }
}
