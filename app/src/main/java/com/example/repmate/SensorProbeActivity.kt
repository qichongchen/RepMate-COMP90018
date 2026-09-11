package com.example.repmate

import android.content.Context
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.repmate.ui.theme.RepMateTheme
import com.repmate.engine.RepEvent
import com.repmate.engine.SquatRepDetector
import com.repmate.sensors.DeviceSensorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * ⚠️ TEMPORARY THROWAWAY — DELETE BEFORE THE REAL WORKOUT UI LANDS. ⚠️
 *
 * A bring-up harness that runs [SquatRepDetector] against live [DeviceSensorSource] frames, so
 * we can see whether thresholds tuned on recorded traces still hold on a phone in a pocket.
 *
 * It is **not** the app. There is no ViewModel, the detector is driven straight from the
 * collector, and UI state lives on the activity — all things the real screen must not do. It
 * exists to be run by hand, read off, and deleted.
 *
 * ### The mismatch this harness was built to measure — now fixed upstream
 * This section used to warn that `smoothingWindow` was 25 **samples**, documented as ~250 ms
 * because the traces were recorded at ~100 Hz, while [DeviceSensorSource] runs at 50 Hz — so
 * the same 25 samples spanned ~500 ms here, twice the intended filter length. The advice was
 * to try `SquatRepDetector(smoothingWindow = 13)` by hand.
 *
 * That parameter no longer exists. [SquatRepDetector] now takes `smoothingWindowMs` (250 ms)
 * and derives the sample count from the observed frame timing on every frame, so this source's
 * 50 Hz gives ~13 samples and a ~99 Hz recording gives ~25 — the same 250 ms of signal either
 * way. The hand-tuning this harness was going to prescribe is unnecessary, and there is no
 * longer a rate-dependent reason to expect under-counting here.
 *
 * The thresholds below stay at their production defaults, and the run is still worth doing:
 * what it measures now is whether thresholds tuned on *recorded* traces hold on a *live*
 * stream, which is a different question from the sample-rate one and still open. Two things to
 * read off the log. First, `smooth=` peaks against `highThreshold` (10.15), as before. Second,
 * `minAmplitude` (0.94) is the guard to watch: it is the only thing now separating a wobble
 * from a rep, its passing band is just 0.15 wide, and both edges of that band come from one
 * participant's recording. A live miscount is most likely to show up there.
 *
 * ### To remove it (do this before merging the real UI)
 * 1. Delete this file.
 * 2. In `AndroidManifest.xml`, delete the `.SensorProbeActivity` entry and uncomment the
 *    MAIN/LAUNCHER `intent-filter` on `.MainActivity` — it is marked with the same warning.
 *
 * ### To read its output
 * Logcat tag `RepMateProbe`: one line per 25 frames, plus one `REP #n` line per counted rep.
 */
class SensorProbeActivity : ComponentActivity() {

    /**
     * Production thresholds — every parameter left at its tuned default.
     *
     * Held as a field, not created inside the collector, so the count survives backgrounding:
     * `repeatOnLifecycle` cancels and restarts collection each time the activity leaves and
     * re-enters the foreground. It does reset on activity recreation (a rotation), which is
     * acceptable for a probe but is exactly the reason the real screen needs a ViewModel.
     */
    private val detector = SquatRepDetector()

    // UI state. On the activity because this is a throwaway; the real screen hoists this into
    // a ViewModel and exposes it as StateFlow.
    private var repCount by mutableIntStateOf(0)
    private var lastRep by mutableStateOf<RepEvent?>(null)
    private var smoothedMagnitude by mutableFloatStateOf(0f)
    private var sampleRateHz by mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val source = DeviceSensorSource(sensorManager)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.i(
                    TAG,
                    "probe started: accelerometer=${source.hasAccelerometer} " +
                        "gyroscope=${source.hasGyroscope} " +
                        "(detector smoothing window = ${SMOOTHING_WINDOW_SAMPLES} samples, " +
                        "~${SMOOTHING_WINDOW_SAMPLES * 1000 / EXPECTED_HZ} ms at ${EXPECTED_HZ} Hz)",
                )
                if (!source.hasAccelerometer) {
                    Log.w(TAG, "no accelerometer on this device — the frame stream will be empty")
                }

                var count = 0
                var windowStartMs = 0L

                source.frames
                    // Keeps the 50 Hz pump off the main thread; the collector below still resumes
                    // on Main, which is what lets it write Compose state directly.
                    .flowOn(Dispatchers.Default)
                    .collect { frame ->
                        val rep = detector.process(frame)
                        smoothedMagnitude = detector.smoothedMagnitude

                        if (rep != null) {
                            repCount = detector.repCount
                            lastRep = rep
                            // RepEvent.index is 0-based; +1 only for the human-facing line.
                            Log.i(
                                TAG,
                                String.format(
                                    Locale.US,
                                    "REP #%d duration=%.2fs amplitude=%.2f window=%d..%d",
                                    rep.index + 1,
                                    (rep.endMs - rep.startMs) / MILLIS_PER_SECOND,
                                    rep.amplitude,
                                    rep.startMs,
                                    rep.endMs,
                                ),
                            )
                        }

                        if (count == 0) windowStartMs = frame.tMillis
                        count++
                        if (count % LOG_EVERY != 0) return@collect

                        val magnitude = sqrt(
                            frame.ax * frame.ax + frame.ay * frame.ay + frame.az * frame.az,
                        )
                        val windowMs = (frame.tMillis - windowStartMs).coerceAtLeast(1L)
                        sampleRateHz = LOG_EVERY * MILLIS_PER_SECOND / windowMs

                        Log.d(
                            TAG,
                            String.format(
                                Locale.US,
                                "n=%d t=%d mag=%.2f smooth=%.2f hz=%.1f reps=%d " +
                                    "gyro=(%+.2f, %+.2f, %+.2f)",
                                count, frame.tMillis, magnitude, detector.smoothedMagnitude,
                                sampleRateHz, detector.repCount, frame.gx, frame.gy, frame.gz,
                            ),
                        )
                        windowStartMs = frame.tMillis
                    }
            }
        }

        setContent {
            RepMateTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ProbeReadout(
                        repCount = repCount,
                        lastRep = lastRep,
                        smoothed = smoothedMagnitude,
                        hz = sampleRateHz,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "RepMateProbe"

        /** One log line per this many frames — ~2 lines a second at 50 Hz, readable in Logcat. */
        const val LOG_EVERY = 25
        const val MILLIS_PER_SECOND = 1000f

        /** Mirrors [SquatRepDetector]'s defaults, for the startup log line only. */
        const val SMOOTHING_WINDOW_SAMPLES = 25
        const val EXPECTED_HZ = 50
    }
}

/**
 * The probe's readout: a rep count big enough to read from the floor mid-squat, and the few
 * numbers worth glancing at while tuning.
 *
 * Stateless and passed only plain values, so it is the one part of this throwaway that would
 * survive into the real UI.
 */
@Composable
private fun ProbeReadout(
    repCount: Int,
    lastRep: RepEvent?,
    smoothed: Float,
    hz: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$repCount",
            fontSize = 160.sp,
            textAlign = TextAlign.Center,
        )
        Text(text = "REPS", fontSize = 24.sp)

        val last = lastRep
        Text(
            modifier = Modifier.padding(top = 32.dp),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            text = if (last == null) {
                "no reps yet"
            } else {
                String.format(
                    Locale.US,
                    "last rep: %.2f s, amplitude %.2f",
                    (last.endMs - last.startMs) / 1000f,
                    last.amplitude,
                )
            },
        )

        // smoothed vs the 10.15 open threshold is the number that explains a miscount.
        Text(
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            text = String.format(
                Locale.US,
                "smoothed %.2f  (opens at 10.15)\n%.1f Hz",
                smoothed,
                hz,
            ),
        )

        Text(
            modifier = Modifier.padding(top = 24.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            text = "TEMPORARY probe — Logcat tag RepMateProbe",
        )
    }
}
