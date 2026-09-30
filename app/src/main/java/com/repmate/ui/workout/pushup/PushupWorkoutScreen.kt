package com.repmate.ui.workout.pushup

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.repmate.BuildConfig
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repmate.engine.PushupRepDetector
import com.repmate.pose.Arm
import com.repmate.pose.CountingStatus
import com.repmate.pose.Tracking
import com.repmate.ui.components.KeepScreenOn
import com.repmate.ui.components.RepMateButton
import com.repmate.ui.components.ScreenLockOverlay
import com.repmate.ui.theme.RepMateTheme
import com.repmate.ui.workout.FeedbackToggles
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
 * ## Which way the screen faces
 * The rear camera is the default: the phone is propped to the side, so the user is not looking at
 * this screen mid-set. The flip button switches to the front camera, which leaves the screen
 * facing the user. Either way every counted rep is also spoken and buzzed (see `RepFeedback`,
 * wired in [PushupWorkoutViewModel]) according to the user's Profile settings -- the feedback does
 * not depend on the camera, so the rear camera, where nobody can glance at the count, is covered
 * by the same path.
 *
 * ## When it is not counting
 * [PushupWorkoutUiState.status] says why (phone moving, no person, arm not clear) and this screen
 * shows it as a hint; counting is paused, not lost, in each case. See [PushupFrameProcessor].
 *
 * ## Screen lock
 * The lock button beside "End workout" is a pocket-safety lock -- see [ScreenLockOverlay]. Named
 * `screenLocked` rather than a bare `locked` so it can't be confused with
 * [PushupWorkoutUiState.armLocked], the unrelated pose-tracking lock. Local `remember` state, not
 * part of [PushupWorkoutUiState], so the screen always opens unlocked.
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
    var screenLocked by remember { mutableStateOf(false) }

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
    KeepScreenOn()

    if (hasPermission) {
        val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
        CameraEffect(
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            facing = uiState.cameraFacing,
            onPose = viewModel::onPose,
        )
        val canSwitchCamera =
            inPreview || context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

        PushupWorkoutContent(
            uiState = uiState,
            previewView = if (inPreview) null else previewView,
            canSwitchCamera = canSwitchCamera,
            onCameraToggled = viewModel::onCameraToggled,
            onMotionLimitsScaled = viewModel::onMotionLimitsScaled,
            onEndWorkoutClicked = viewModel::onEndWorkoutClicked,
            onHapticFeedbackToggled = viewModel::onHapticFeedbackToggled,
            onSpokenRepCountToggled = viewModel::onSpokenRepCountToggled,
            screenLocked = screenLocked,
            onScreenLockToggled = { screenLocked = !screenLocked },
            modifier = modifier,
        )
    } else {
        CameraPermissionRationale(
            onGrantClicked = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            modifier = modifier,
        )
    }
}

/**
 * Binds the camera + pose analyzer to [lifecycleOwner]'s lifecycle, unbinding on dispose. Keyed on
 * [facing] as well, so switching camera unbinds the old one, closes its analyzer and binds afresh.
 */
@Composable
private fun CameraEffect(
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    facing: CameraFacing,
    onPose: (Arm?, Arm?) -> Unit,
) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current

    DisposableEffect(lifecycleOwner, facing) {
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
                    val selector =
                        when (facing) {
                            CameraFacing.REAR -> CameraSelector.DEFAULT_BACK_CAMERA
                            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    Log.i(TAG, "camera bound: $facing")
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
    canSwitchCamera: Boolean,
    onCameraToggled: () -> Unit,
    onMotionLimitsScaled: (Double) -> Unit,
    onEndWorkoutClicked: () -> Unit,
    onHapticFeedbackToggled: (Boolean) -> Unit,
    onSpokenRepCountToggled: (Boolean) -> Unit,
    screenLocked: Boolean,
    onScreenLockToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hardcoded white/black rather than theme colors, same as the rest of this overlay: it has to
    // stay legible over the camera preview in both themes.
    ScreenLockOverlay(
        screenLocked = screenLocked,
        onScreenLockToggled = onScreenLockToggled,
        scrimColor = Color.Black,
        iconTint = Color.White,
        outlineColor = Color.White.copy(alpha = 0.6f),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                StatusBadge(status = uiState.status)

                StatusHint(status = uiState.status)

                if (!uiState.armLocked) {
                    Spacer(modifier = Modifier.height(16.dp))
                    PlacementInstructions(facing = uiState.cameraFacing)
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
                        text = "phase: ${uiState.phase.name.lowercase(Locale.US)} · " +
                            "${uiState.cameraFacing.name.lowercase(Locale.US)} camera",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FeedbackToggles(
                        hapticEnabled = uiState.hapticFeedbackEnabled,
                        spokenEnabled = uiState.spokenRepCountEnabled,
                        onHapticToggled = onHapticFeedbackToggled,
                        onSpokenToggled = onSpokenRepCountToggled,
                        // Off while locked so a pocket touch (or an accessibility action) can't flip them.
                        enabled = !screenLocked,
                        // White, like the rest of this overlay, to stay legible over the camera preview.
                        tint = Color.White,
                    )
                    if (BuildConfig.DEBUG) {
                        uiState.motion?.let {
                            Spacer(modifier = Modifier.height(16.dp))
                            MotionDebugReadout(motion = it, onLimitsScaled = onMotionLimitsScaled)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RepMateButton(
                        text = "End workout",
                        onClick = onEndWorkoutClicked,
                        modifier = Modifier.weight(1f),
                    )
                    if (canSwitchCamera) {
                        CameraFlipButton(facing = uiState.cameraFacing, onClick = onCameraToggled)
                    }
                    ScreenLockButton()
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    status: CountingStatus,
    modifier: Modifier = Modifier,
) {
    val (color, label) =
        when (status) {
            CountingStatus.COUNTING -> Color(0xFF43A047) to "tracking"
            CountingStatus.PHONE_MOVING -> Color(0xFFFFB300) to "phone moving - paused"
            CountingStatus.NO_PERSON -> Color(0xFFE53935) to "no person detected"
            CountingStatus.LOW_CONFIDENCE -> Color(0xFFFFB300) to "low confidence - paused"
            CountingStatus.FINDING_ARM -> Color(0xFFFFB300) to "finding your arm..."
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

/** The reason counting is paused, spelled out; nothing at all while counting or still finding the arm. */
@Composable
private fun StatusHint(
    status: CountingStatus,
    modifier: Modifier = Modifier,
) {
    val text =
        when (status) {
            CountingStatus.PHONE_MOVING -> "Hold the phone still. Counting is paused until it settles."
            CountingStatus.NO_PERSON -> "No person detected. Step into frame - counting is paused."
            CountingStatus.LOW_CONFIDENCE -> "Can't see your arm clearly. Counting is paused."
            CountingStatus.COUNTING, CountingStatus.FINDING_ARM -> return
        }
    Spacer(modifier = Modifier.height(16.dp))
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color(0xCCB26A00), MaterialTheme.shapes.medium)
                .padding(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlacementInstructions(
    facing: CameraFacing,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                .padding(16.dp),
    ) {
        Text(
            text =
                when (facing) {
                    CameraFacing.REAR -> "Prop your phone to the side, at floor level, so your whole body is in frame."
                    CameraFacing.FRONT ->
                        "Prop your phone to the side at floor level, screen facing you, " +
                            "so your whole body is in frame."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Same circular outline treatment as the screen-lock button beside it. */
@Composable
private fun CameraFlipButton(
    facing: CameraFacing,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
    ) {
        Icon(
            imageVector = Icons.Filled.Cameraswitch,
            contentDescription =
                when (facing) {
                    CameraFacing.REAR -> "Switch to front camera"
                    CameraFacing.FRONT -> "Switch to rear camera"
                },
            tint = Color.White,
        )
    }
}

/**
 * Debug builds only: the stability gate's live readings against its limits, with buttons to scale
 * both limits, so they can be tuned on the phone without rebuilding. Scaling is not saved; once a
 * value feels right, it goes into [com.repmate.sensors.StabilityConfig]'s defaults.
 */
@Composable
private fun MotionDebugReadout(
    motion: MotionReadout,
    onLimitsScaled: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "phone motion ${(motion.level * 100).toInt()}% of limit",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        Text(
            text = "gyro %.3f / %.3f rad/s".format(Locale.US, motion.gyroRmsRadPerSec, motion.gyroLimitRadPerSec),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
        Text(
            text = "accel %.3f / %.3f m/s2".format(Locale.US, motion.accelStdMps2, motion.accelLimitMps2),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
        Row {
            TextButton(onClick = { onLimitsScaled(1 / LIMIT_STEP) }) { Text("tighter", color = Color.White) }
            TextButton(onClick = { onLimitsScaled(LIMIT_STEP) }) { Text("looser", color = Color.White) }
        }
    }
}

private const val LIMIT_STEP = 1.25

// Buzz on, spoken off: the app's defaults.
private val PREVIEW_STATE =
    PushupWorkoutUiState(
        repCount = 6,
        phase = PushupRepDetector.Phase.UP,
        tracking = Tracking.TRACKING,
        armLocked = true,
        elapsedMillis = 42_000L,
        status = CountingStatus.COUNTING,
    )

@Preview(name = "Push-up workout - tracking", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutTrackingPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PREVIEW_STATE,
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = false,
            onScreenLockToggled = {},
        )
    }
}

@Preview(name = "Push-up workout - finding arm", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutFindingArmPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PushupWorkoutUiState(tracking = Tracking.TRACKING, status = CountingStatus.FINDING_ARM),
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = false,
            onScreenLockToggled = {},
        )
    }
}

@Preview(name = "Push-up workout - screen locked", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutScreenLockedPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PREVIEW_STATE,
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = true,
            onScreenLockToggled = {},
        )
    }
}

@Preview(name = "Push-up workout - phone moving", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutPhoneMovingPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PREVIEW_STATE.copy(status = CountingStatus.PHONE_MOVING),
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = false,
            onScreenLockToggled = {},
        )
    }
}

@Preview(name = "Push-up workout - no person, front camera", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutNoPersonFrontPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState =
                PREVIEW_STATE.copy(
                    status = CountingStatus.NO_PERSON,
                    tracking = Tracking.NO_PERSON,
                    cameraFacing = CameraFacing.FRONT,
                ),
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = false,
            onScreenLockToggled = {},
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

@Preview(name = "Push-up workout - feedback both on", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutFeedbackOnPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PREVIEW_STATE.copy(hapticFeedbackEnabled = true, spokenRepCountEnabled = true),
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = false,
            onScreenLockToggled = {},
        )
    }
}

@Preview(name = "Push-up workout - feedback both off", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PushupWorkoutFeedbackOffPreview() {
    RepMateTheme {
        PushupWorkoutContent(
            uiState = PREVIEW_STATE.copy(hapticFeedbackEnabled = false, spokenRepCountEnabled = false),
            previewView = null,
            canSwitchCamera = true,
            onCameraToggled = {},
            onMotionLimitsScaled = {},
            onEndWorkoutClicked = {},
            onHapticFeedbackToggled = {},
            onSpokenRepCountToggled = {},
            screenLocked = false,
            onScreenLockToggled = {},
        )
    }
}
