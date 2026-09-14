package com.example.repmate

import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import com.repmate.engine.Burst
import com.repmate.engine.BurstDetector
import com.repmate.engine.CalibrationOutcome
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.JumpingJackRepDetector
import com.repmate.engine.MotionFrame
import com.repmate.engine.RepEvent
import com.repmate.engine.SquatRepDetector
import com.repmate.sensors.DeviceSensorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * ⚠️ TEMPORARY THROWAWAY — DELETE BEFORE THE REAL WORKOUT UI LANDS. ⚠️
 *
 * A bring-up harness that runs one of the two rep detectors against live
 * [DeviceSensorSource] frames, so we can see whether thresholds tuned on recorded traces
 * still hold on a phone in a pocket.
 *
 * It is **not** the app. There is no ViewModel, the detector is driven straight from the
 * collector, and UI state lives on the activity — all things the real screen must not do. It
 * exists to be run by hand, read off, and deleted.
 *
 * ### What to read off, per detector
 * Both detectors share the same trigger (10.15 open / 9.7 close) and the same 250 ms
 * time-based filter, so `smooth=` in the log means the same thing either way. What differs is
 * the guard most likely to misbehave live:
 *
 * - **Squat** — watch `minAmplitude` (0.94). It is the only thing separating a wobble from a
 *   rep, its passing band is 0.15 wide, and both edges come from one participant's recording.
 * - **Jumping jack** — watch `minBurstAmplitude` (4.1) and the pairing. A jumping jack is
 *   **two** impacts, a soft jump-out then a harder landing, and a rep is counted only when
 *   that pair arrives in that order. The readout shows whether a jump-out is currently held
 *   waiting for its landing, which is the fastest way to see pairing fail: a count that stalls
 *   showing "awaiting landing" means landings are not clearing the trigger, while one that
 *   stalls without it means jump-outs are not.
 *
 * ### The metronome, and why it is not optional for jumping jacks
 * [JumpingJackRepDetector] assumes a **paced** set. The 2.0-bursts-per-rep ratio its pairing
 * depends on only holds at a steady cadence; unpaced at roughly one rep a second it collapses
 * to 1.2, because a gentle take-off never lifts the smoothed magnitude clear of the trigger.
 * An unpaced live test will therefore under-count, and that is the movement's fault rather
 * than the detector's — so pacing to the beep is part of the measurement, not a convenience.
 *
 * The default tempo is the cadence of the two recordings the thresholds were validated
 * against. Measured rep-to-rep across both: **45.5 - 56.7 BPM**, with sustained means of 49.2
 * and 53.9 and a pooled mean of **51.7** — hence [DEFAULT_BPM]. The tempo is adjustable
 * because how far the pairing model stretches is worth knowing, but anything above ~57 BPM is
 * faster than it has ever been shown to work at, and a miscount up there is not evidence of a
 * bug.
 *
 * **One beep = one full jumping jack** (out *and* back), not one impact.
 *
 * ### Reading the diagnostic log
 * The detectors cannot report *why* a window was discarded — `process` returns null both when
 * nothing closed and when a guard threw the window away. So the probe runs a second copy of
 * the same trigger with every guard switched off ([shadow]) and re-applies the guards itself,
 * giving one `WINDOW` line for **every** window opened, survivor or not:
 *
 * ```
 *   WINDOW [SQUAT] +12.41s..+13.06s (t=...) dur=650ms amp=1.842 peak=11.61 floor=9.62
 *       -> ACCEPT rep (1180ms since last rep)
 *   WINDOW [SQUAT] +13.20s..+13.48s (t=...) dur=280ms amp=1.105 peak=11.02 floor=9.65
 *       -> REJECT minRepDurationMs (280ms < 550ms)
 * ```
 *
 * `amp` is peak-to-peak, the figure the guards actually test. `peak` and `floor` are the
 * highest and lowest smoothed values inside the window, which `amp` alone cannot tell apart:
 * a window that barely cleared 10.15 and one that reached 13 can share an amplitude, and for
 * an over-count they are different diagnoses.
 *
 * Elapsed seconds are counted from the first frame of the run, so the log can be read against
 * what the body was doing — standing still, squatting, retrieving the phone.
 *
 * A `DISAGREE` line means the shadow's verdict and the detector's behaviour diverged. That is
 * a bug in the diagnostic, not the detector: believe the `REP` lines. `ProbeShadowTest` checks
 * the two agree across every recorded trace, so a DISAGREE in the field is worth reporting.
 *
 * ### Calibration
 * **Calibrate** derives a [CalibrationProfile] from five of the user's own reps and installs
 * it, so the rest of the run is counted with that person's thresholds instead of the tuned
 * defaults. It is the answer to a miscount whose cause is "this user does not move like the
 * recordings" rather than "a guard is wrong".
 *
 * Two details matter more than they look.
 *
 * **Capture uses the [shadow], not the detector.** Calibration exists precisely for users the
 * default guards mis-handle, so capturing through those guards could not work: the reps worth
 * measuring are the ones being rejected. The guard-free trigger sees every window, which is
 * the only source that can measure a rep the current thresholds throw away.
 *
 * **Capture is paced, and the metronome is not optional here.** [CalibrationProfile] warns
 * that its consistency gate is a backstop against gross nonsense rather than a substitute for
 * capturing the right windows — a set polluted by a rebound can pass the gate and yield a
 * profile that is quietly wrong. So calibration prompts: one beep is one rep, and only the
 * largest window between two beeps becomes a sample. A rebound is never the largest window in
 * its own beat, which is what keeps it out of the profile. Pacing also makes the gaps between
 * samples mean something, which is what [CalibrationProfile.cooldownMs] is derived from.
 *
 * Set the tempo to the cadence you actually squat at before pressing Calibrate; the profile
 * can only describe the movement it was shown.
 *
 * A profile lives in memory for the life of the activity — this is a probe, not the app, and
 * nothing here persists. It survives detector switches so squat/jack/squat does not silently
 * drop back to the defaults, and **Use defaults** discards it.
 *
 * ### To remove it (do this before merging the real UI)
 * 1. Delete this file and `app/src/test/java/com/example/repmate/ProbeShadowTest.kt`.
 * 2. In `AndroidManifest.xml`, delete the `.SensorProbeActivity` entry and uncomment the
 *    MAIN/LAUNCHER `intent-filter` on `.MainActivity` — it is marked with the same warning.
 *
 * Nothing else needs undoing. The metronome uses [ToneGenerator], which needs no permission,
 * so there is no `uses-permission` left behind in the manifest to strip out.
 *
 * ### To read its output
 * Logcat tag `RepMateProbe`: one line per 25 frames, plus one `REP #n` line per counted rep.
 * Every line carries the active detector, and switching logs its own line, so a log read back
 * later is unambiguous about which detector produced which reps.
 */
class SensorProbeActivity : ComponentActivity() {

    /**
     * The detector currently being driven, at its production defaults.
     *
     * Replaced wholesale when the mode is switched, which is how the count resets: a fresh
     * instance has no reps, no filter history and no half-formed pair. That is deliberately
     * simpler than calling `reset()` — there is no leftover state to get wrong.
     *
     * Read on the collector and written from a button press. Both run on the main dispatcher
     * (the flow's `flowOn` moves only the upstream pump off it), so there is no race here.
     */
    private var detector: ProbeDetector by mutableStateOf(ProbeDetector.Squat())

    // UI state. On the activity because this is a throwaway; the real screen hoists this into
    // a ViewModel and exposes it as StateFlow.
    private var repCount by mutableIntStateOf(0)
    private var lastRep by mutableStateOf<RepEvent?>(null)
    private var smoothedMagnitude by mutableFloatStateOf(0f)
    private var sampleRateHz by mutableFloatStateOf(0f)
    private var awaitingLanding by mutableStateOf(false)

    // Metronome state.
    private var metronomeRunning by mutableStateOf(false)
    private var bpm by mutableIntStateOf(DEFAULT_BPM)
    private var metronomeJob: Job? = null

    /**
     * The calibration currently in force, or null while the tuned defaults are running.
     *
     * Held on the activity rather than inside [ProbeDetector.Squat] so it survives a switch to
     * jumping jacks and back: re-deriving a profile because the mode was toggled would be five
     * pointless squats.
     */
    private var activeProfile by mutableStateOf<CalibrationProfile?>(null)

    /** True while calibration samples are being captured. */
    private var calibrating by mutableStateOf(false)

    /** Samples captured so far this calibration run, one per prompted beat. */
    private var calibrationSamples by mutableStateOf<List<RepEvent>>(emptyList())

    /** Verdict of the last completed calibration, kept so the screen can show why one failed. */
    private var calibrationOutcome by mutableStateOf<CalibrationOutcome?>(null)

    /**
     * Beat counter, incremented by the metronome and read by the collector.
     *
     * The two clocks in this file do not compare: frames carry boot-clock timestamps while the
     * metronome schedules against `System.currentTimeMillis`. Rather than convert between them
     * — which would be a guess about the offset — the metronome only says *that* a beat
     * happened, and the collector stamps it with the frame clock on the next frame. Both run
     * on the main dispatcher, so a plain counter is enough.
     */
    private var beatSeq by mutableIntStateOf(0)

    /** The last value of [beatSeq] the collector has acted on. */
    private var observedBeatSeq = 0

    /**
     * Largest window seen since the current beat, or null if none has closed yet.
     *
     * One rep per beat, so the biggest window between two beeps is the rep and everything else
     * in that beat is a rebound. Taking the largest rather than the first is what keeps a
     * rebound out of the profile even when it precedes the rep it belongs to.
     */
    private var bestInBeat: Burst? = null

    /**
     * A second, guard-free view of the same signal, used only for diagnostics.
     *
     * [SquatRepDetector.process] returns null both when no window closed *and* when a window
     * closed but a guard rejected it, so from outside there is no way to see a rejection. This
     * runs the identical trigger and filter with every guard switched off, so every window the
     * real detector opens appears here whether it survived or not. The guard that would have
     * killed it is re-applied in [ProbeDetector.explain], in the order the real detector
     * applies them.
     *
     * It is a shadow, not a substitute: the count on screen still comes from the real detector,
     * and the shadow's verdict is cross-checked against it on every window. A `DISAGREE` line
     * in the log means this diagnostic is wrong, not the detector.
     */
    private var shadow = BurstDetector()

    /**
     * Recent (timestamp, smoothed magnitude) samples, trimmed to [HISTORY_MS].
     *
     * Kept so a closing window can report the smoothed **peak** inside it. The detector reports
     * amplitude as peak-to-peak, which cannot tell "barely cleared the threshold" from "rose a
     * long way from a high floor" — and for an over-count those are different diagnoses.
     */
    private val smoothedHistory = ArrayDeque<Sample>()

    /**
     * Timestamp of the first frame of this collection, so windows can be reported as seconds
     * into the run.
     *
     * Frame timestamps come from the boot clock and are large and arbitrary; "+12.4s" is what
     * lets a log be read against "stood still 3 s, then squatted".
     */
    private var firstFrameMs: Long? = null

    /**
     * Created on first beat and kept for the activity's life: [ToneGenerator] allocates an
     * audio track, so building one per beat would stutter the tempo it exists to keep.
     * Nullable because the constructor can throw when audio resources are unavailable, and a
     * probe that cannot beep should still count reps.
     */
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val source = DeviceSensorSource(sensorManager)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.i(
                    TAG,
                    "probe started: accelerometer=${source.hasAccelerometer} " +
                        "gyroscope=${source.hasGyroscope} detector=${detector.label}",
                )
                // Printed every run so a log can be read months later without guessing which
                // build produced it, and so a threshold change is visible in the log itself.
                Log.i(TAG, "guards: $GUARD_SUMMARY")
                // GUARD_SUMMARY is the compiled-in defaults; this is what is actually running,
                // which differs once a calibration is installed.
                Log.i(TAG, "active guards [${detector.label}]: ${detector.guardSummary}")
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
                        // Read the field every frame rather than capturing it once: the mode can
                        // change mid-collection, and the next frame must go to the new detector.
                        if (firstFrameMs == null) firstFrameMs = frame.tMillis
                        val active = detector
                        val rep = active.process(frame)
                        smoothedMagnitude = active.smoothedMagnitude
                        awaitingLanding = active.awaitingLanding

                        // A beep landed since the last frame: the beat that just ended is
                        // complete, so whatever its largest window was is now a sample.
                        if (calibrating && beatSeq != observedBeatSeq) {
                            observedBeatSeq = beatSeq
                            commitBeat()
                        }

                        // Diagnostics run on their own copy of the trigger, so a window that
                        // the real detector silently discarded still shows up below.
                        recordSmoothed(frame.tMillis, active.smoothedMagnitude)
                        val burst = shadow.process(frame)
                        if (burst != null) logWindow(burst, active, emitted = rep)
                        // Calibration measures the guard-free windows on purpose: the reps it
                        // exists to learn from are the ones the current guards reject.
                        if (burst != null && calibrating) {
                            val best = bestInBeat
                            if (best == null || burst.amplitude > best.amplitude) bestInBeat = burst
                        }

                        if (rep != null) {
                            repCount = active.repCount
                            lastRep = rep
                            // RepEvent.index is 0-based; +1 only for the human-facing line.
                            Log.i(
                                TAG,
                                String.format(
                                    Locale.US,
                                    "REP #%d [%s] duration=%.2fs amplitude=%.2f window=%d..%d",
                                    rep.index + 1,
                                    active.label,
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
                                "n=%d t=%d mag=%.2f smooth=%.2f hz=%.1f reps=%d [%s] " +
                                    "gyro=(%+.2f, %+.2f, %+.2f)",
                                count, frame.tMillis, magnitude, active.smoothedMagnitude,
                                sampleRateHz, active.repCount, active.label,
                                frame.gx, frame.gy, frame.gz,
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
                        detectorLabel = detector.label,
                        isJumpingJack = detector is ProbeDetector.JumpingJack,
                        awaitingLanding = awaitingLanding,
                        repCount = repCount,
                        lastRep = lastRep,
                        smoothed = smoothedMagnitude,
                        hz = sampleRateHz,
                        bpm = bpm,
                        metronomeRunning = metronomeRunning,
                        guardSummary = detector.guardSummary,
                        calibrating = calibrating,
                        calibrationCaptured = calibrationSamples.size,
                        calibrationStatus = calibrationStatusText(),
                        hasProfile = activeProfile != null,
                        onSelectSquat = ::selectSquat,
                        onSelectJumpingJack = ::selectJumpingJack,
                        onStartCalibration = ::startCalibration,
                        onClearCalibration = ::clearCalibration,
                        onBpmChange = { step -> bpm = (bpm + step).coerceIn(MIN_BPM, MAX_BPM) },
                        onToggleMetronome = {
                            if (metronomeRunning) stopMetronome() else startMetronome()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    /** Appends one smoothed reading and drops anything older than [HISTORY_MS]. */
    private fun recordSmoothed(tMillis: Long, smoothed: Float) {
        smoothedHistory.addLast(Sample(tMillis, smoothed))
        while (smoothedHistory.isNotEmpty() && tMillis - smoothedHistory.first().tMillis > HISTORY_MS) {
            smoothedHistory.removeFirst()
        }
    }

    /**
     * Logs one closed window: its timing, its swing, the smoothed peak inside it, and either
     * the rep it became or the guard that discarded it.
     *
     * @param burst the window the shadow trigger just closed.
     * @param active the live detector, asked which guard would reject this window.
     * @param emitted the rep the live detector actually produced on this frame, or null.
     */
    private fun logWindow(burst: Burst, active: ProbeDetector, emitted: RepEvent?) {
        val inside = smoothedHistory.filter { it.tMillis in burst.startMs..burst.endMs }
        val peak = inside.maxOfOrNull { it.smoothed } ?: Float.NaN
        val floor = inside.minOfOrNull { it.smoothed } ?: Float.NaN

        val verdict = active.explain(burst)

        val origin = firstFrameMs ?: burst.startMs
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "WINDOW [%s] +%.2fs..+%.2fs (t=%d..%d) dur=%dms amp=%.3f peak=%.3f floor=%.3f -> %s",
                active.label,
                (burst.startMs - origin) / MILLIS_PER_SECOND,
                (burst.endMs - origin) / MILLIS_PER_SECOND,
                burst.startMs, burst.endMs, burst.durationMs,
                burst.amplitude, peak, floor, verdict,
            ),
        )

        // Self-check. The shadow replays the same guards in the same order, so its verdict must
        // agree with what the detector actually did. If these ever diverge the diagnostic is
        // lying, and that is worth knowing before any threshold is touched on its evidence.
        val shadowAccepted = verdict.startsWith(ACCEPT)
        if (shadowAccepted != (emitted != null)) {
            Log.e(
                TAG,
                "DISAGREE: shadow says '$verdict' but detector " +
                    (if (emitted == null) "emitted nothing" else "emitted a rep") +
                    " -- trust the detector, not this line",
            )
        }
    }

    /** Swaps the detector and clears every reading that belonged to the old one. */
    private fun switchTo(next: ProbeDetector) {
        if (next.label == detector.label) return
        Log.i(TAG, "detector switched: ${detector.label} -> ${next.label} (count reset)")
        installDetector(next)
    }

    /** Selecting squats keeps any calibration; it is discarded only by [clearCalibration]. */
    private fun selectSquat() {
        switchTo(ProbeDetector.Squat(activeProfile))
    }

    /** Leaving squats mid-calibration abandons the run: jumping-jack windows are not samples. */
    private fun selectJumpingJack() {
        if (detector is ProbeDetector.JumpingJack) return
        cancelCalibration("switched to jumping jacks")
        switchTo(ProbeDetector.JumpingJack())
    }

    /**
     * Installs a detector and clears every reading that belonged to the old one.
     *
     * Separate from [switchTo] because installing a *newly calibrated* squat detector is not a
     * mode change — the label is unchanged — but still has to reset everything, since the reps
     * on screen were counted against thresholds that no longer apply.
     */
    private fun installDetector(next: ProbeDetector) {
        detector = next
        // Logged on every install, not just at startup, so a log read back later shows which
        // thresholds counted each stretch of reps — defaults, or which calibration.
        Log.i(TAG, "active guards [${next.label}]: ${next.guardSummary}")
        // The shadow carries filter state and a cooldown anchor of its own; a stale one would
        // mis-attribute the first window after a switch. It also has to track the profile's
        // maxRepDurationMs: a shadow that abandons windows at a different length than the
        // detector would report DISAGREE on every long window.
        shadow = newShadow()
        smoothedHistory.clear()
        firstFrameMs = null
        repCount = 0
        lastRep = null
        smoothedMagnitude = 0f
        awaitingLanding = false
    }

    /** A guard-free trigger matching the active profile's window-abandonment length. */
    private fun newShadow(): BurstDetector = BurstDetector(
        maxBurstDurationMs = activeProfile?.maxRepDurationMs
            ?: SquatRepDetector.DEFAULT_MAX_REP_DURATION_MS,
    )

    /**
     * Begins a paced calibration run: five prompted reps, one per beep.
     *
     * The metronome is started rather than merely suggested. Capture windows are beats, so
     * without one there is nothing to capture into — and an unpaced set is exactly the
     * pollution risk [CalibrationProfile] warns its consistency gate will not catch.
     */
    private fun startCalibration() {
        calibrationSamples = emptyList()
        calibrationOutcome = null
        bestInBeat = null
        observedBeatSeq = beatSeq
        calibrating = true
        // Fresh trigger state, so a window half-open from before the button press cannot
        // become the first sample.
        installDetector(ProbeDetector.Squat(activeProfile))
        if (!metronomeRunning) startMetronome()
        Log.i(
            TAG,
            "CALIBRATION started: ${CalibrationProfile.REQUIRED_SAMPLES} reps at $bpm BPM, " +
                "one beep = one squat",
        )
    }

    /** Ends the beat that just finished, promoting its largest window to a sample. */
    private fun commitBeat() {
        val best = bestInBeat ?: return
        bestInBeat = null

        val samples = calibrationSamples + RepEvent(
            index = calibrationSamples.size,
            startMs = best.startMs,
            endMs = best.endMs,
            amplitude = best.amplitude,
        )
        calibrationSamples = samples

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "CALIBRATION sample %d/%d: dur=%dms amp=%.3f",
                samples.size, CalibrationProfile.REQUIRED_SAMPLES,
                best.durationMs, best.amplitude,
            ),
        )

        if (samples.size >= CalibrationProfile.REQUIRED_SAMPLES) finishCalibration()
    }

    /** Derives a profile from the captured samples and installs it, or reports why it could not. */
    private fun finishCalibration() {
        calibrating = false
        bestInBeat = null
        stopMetronome()

        val outcome = CalibrationProfile.evaluate(calibrationSamples)
        calibrationOutcome = outcome

        when (outcome) {
            is CalibrationOutcome.Accepted -> {
                activeProfile = outcome.profile
                // describe() is logged whole and deliberately: it names the raw measurement
                // behind each threshold and flags anything a safety bound moved, which is the
                // difference between a profile that can be argued about and one that cannot.
                Log.i(TAG, "CALIBRATION accepted\n${outcome.profile.describe()}")
                if (outcome.profile.wasClamped) {
                    Log.w(
                        TAG,
                        "CALIBRATION clamped: a derived threshold hit a safety bound, so the " +
                            "profile is not purely this user's movement — see the notes above",
                    )
                }
                installDetector(ProbeDetector.Squat(outcome.profile))
            }

            is CalibrationOutcome.Rejected -> {
                Log.w(TAG, "CALIBRATION rejected (${outcome.reason}): ${outcome.detail}")
                Log.w(TAG, "still running the tuned defaults — calibrate again")
            }
        }
    }

    /** Abandons a calibration run without deriving anything. */
    private fun cancelCalibration(why: String) {
        if (!calibrating) return
        calibrating = false
        bestInBeat = null
        Log.i(
            TAG,
            "CALIBRATION cancelled ($why) after " +
                "${calibrationSamples.size}/${CalibrationProfile.REQUIRED_SAMPLES} samples",
        )
    }

    /**
     * One line for the readout describing the last calibration, or null if there has been none.
     *
     * A clamp is shown on screen, not only logged: a profile a safety bound moved is not purely
     * this user's movement, and that is the first thing to know when it still miscounts.
     */
    private fun calibrationStatusText(): String? = when (val outcome = calibrationOutcome) {
        null -> null
        is CalibrationOutcome.Rejected -> "calibration rejected: ${outcome.reason} — try again"
        is CalibrationOutcome.Accepted -> {
            val p = outcome.profile
            val clamped = p.notes.filter { it.contains("CLAMPED") }
                .joinToString("\n") { it.substringBefore(" (") }
            String.format(
                Locale.US,
                "calibrated from reps %d-%d ms, softest %.2f, fastest gap %d ms",
                p.shortestSampleMs, p.longestSampleMs, p.softestSampleAmplitude,
                p.fastestSampleGapMs,
            ) + if (clamped.isEmpty()) "" else "\n$clamped"
        }
    }

    /** Discards the calibration and goes back to the tuned defaults. */
    private fun clearCalibration() {
        cancelCalibration("defaults restored")
        activeProfile = null
        calibrationSamples = emptyList()
        calibrationOutcome = null
        Log.i(TAG, "CALIBRATION cleared — back to the tuned defaults")
        installDetector(ProbeDetector.Squat(null))
    }

    /**
     * Beats on an absolute schedule rather than sleeping for the interval in a loop.
     *
     * A loop that delays by the interval accumulates each beat's scheduling latency and drifts
     * slow. For a metronome whose entire purpose is a steady cadence, that would quietly
     * invalidate the thing being measured. Each beat is scheduled against a running target
     * instead, and the tempo is re-read every beat so the +/- buttons take effect immediately.
     */
    private fun startMetronome() {
        metronomeJob?.cancel()
        metronomeRunning = true
        Log.i(TAG, "metronome started at $bpm BPM (one beep = one full rep)")
        metronomeJob = lifecycleScope.launch {
            var nextBeatMs = System.currentTimeMillis()
            while (isActive) {
                beep()
                // Tells the collector a beat boundary passed; it stamps the time itself, off
                // the frame clock, because these two clocks are not comparable.
                beatSeq++
                nextBeatMs += MILLIS_PER_MINUTE / bpm
                delay((nextBeatMs - System.currentTimeMillis()).coerceAtLeast(0L))
            }
        }
    }

    private fun stopMetronome() {
        metronomeJob?.cancel()
        metronomeJob = null
        metronomeRunning = false
        Log.i(TAG, "metronome stopped")
        // Beats are the capture windows, so a run that loses its pacing is not a calibration
        // any more. Better to abandon it loudly than to derive a profile from a half-paced set.
        cancelCalibration("metronome stopped")
    }

    /** One short tone. Failure is swallowed: a silent probe still counts reps. */
    private fun beep() {
        val generator = toneGenerator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
        }.onFailure {
            Log.w(TAG, "no tone generator available — the metronome will be silent", it)
        }.getOrNull()?.also { toneGenerator = it } ?: return

        runCatching { generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS) }
    }

    override fun onStop() {
        super.onStop()
        // The metronome must not keep beeping from a pocket after the probe is left. Frame
        // collection is already lifecycle-scoped; this gives the tone the same rule.
        if (metronomeRunning) stopMetronome()
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
    }

    private companion object {
        const val TAG = "RepMateProbe"

        /** One log line per this many frames — ~2 lines a second at 50 Hz, readable in Logcat. */
        const val LOG_EVERY = 25
        const val MILLIS_PER_SECOND = 1000f
        const val MILLIS_PER_MINUTE = 60_000L

        /**
         * Pooled mean cadence of the two recordings [JumpingJackRepDetector] was validated
         * against: 45.5-56.7 BPM across both, with sustained means of 49.2 and 53.9.
         */
        const val DEFAULT_BPM = 52
        const val MIN_BPM = 40
        const val MAX_BPM = 70

        const val TONE_VOLUME = 100
        const val TONE_DURATION_MS = 120

        /** How much smoothed signal to keep for computing a closed window's peak. */
        const val HISTORY_MS = 10_000L

        const val ACCEPT = "ACCEPT"

        /**
         * The thresholds actually in force, read from the detectors rather than retyped, so
         * this line cannot drift away from what is running.
         */
        val GUARD_SUMMARY: String = buildString {
            append("squat[high=${SquatRepDetector.DEFAULT_HIGH_THRESHOLD} ")
            append("low=${SquatRepDetector.DEFAULT_LOW_THRESHOLD} ")
            append("minDur=${SquatRepDetector.DEFAULT_MIN_REP_DURATION_MS} ")
            append("maxDur=${SquatRepDetector.DEFAULT_MAX_REP_DURATION_MS} ")
            append("cooldown=${SquatRepDetector.DEFAULT_COOLDOWN_MS} ")
            append("minAmp=${SquatRepDetector.DEFAULT_MIN_AMPLITUDE} ")
            append("smoothing=${SquatRepDetector.DEFAULT_SMOOTHING_WINDOW_MS}ms] ")
            append("jack[minAmp=${JumpingJackRepDetector.DEFAULT_MIN_BURST_AMPLITUDE} ")
            append("minDur=${JumpingJackRepDetector.DEFAULT_MIN_BURST_DURATION_MS}]")
        }
    }
}

/**
 * The two detectors behind one readout, so the probe can drive either of them.
 *
 * [SquatRepDetector] and [JumpingJackRepDetector] deliberately share no common type: giving
 * them one would mean editing the squat detector, which is pinned by replay tests against four
 * participants. This adapter exists only so this throwaway screen can hold "whichever detector
 * is active", and it goes in the bin with the rest of the file.
 */
internal sealed class ProbeDetector {

    abstract fun process(frame: MotionFrame): RepEvent?
    abstract val repCount: Int
    abstract val smoothedMagnitude: Float
    abstract val label: String

    /**
     * The thresholds this instance is actually running, read from the instance rather than
     * retyped, so a log or a readout cannot claim guards that are not in force. For squats
     * that changes with calibration, which is the whole reason this is per-instance.
     */
    abstract val guardSummary: String

    /** Only meaningful for jumping jacks; always false for a one-burst-per-rep detector. */
    open val awaitingLanding: Boolean get() = false

    /**
     * Replays this detector's guards against one closed window and says what happened to it.
     *
     * Mirrors the real guard logic rather than observing it, because the detectors do not
     * report rejections. Each implementation applies the same guards **in the same order** as
     * the detector it shadows — order matters, since only the first failing guard is reported
     * and the real code returns at the first failure too.
     *
     * Stateful: an accepted window arms the cooldown, exactly as it does inside the detector.
     * A rejected one deliberately does not, which is why a rejected window cannot suppress the
     * rep that follows it.
     */
    abstract fun explain(burst: Burst): String

    /**
     * @param profile the user's calibration, or null to run the tuned defaults.
     */
    class Squat(private val profile: CalibrationProfile? = null) : ProbeDetector() {
        private val detector = SquatRepDetector(profile)

        // Read from the profile, or the defaults when there is none — exactly the fallback
        // SquatRepDetector's calibration constructor applies. The shadow must resolve the
        // thresholds the same way the detector did or every verdict would be about a
        // different set of guards, and the DISAGREE check would fire on every window.
        private val minDur = profile?.minRepDurationMs
            ?: SquatRepDetector.DEFAULT_MIN_REP_DURATION_MS
        private val minAmp = profile?.minAmplitude ?: SquatRepDetector.DEFAULT_MIN_AMPLITUDE
        private val cooldown = profile?.cooldownMs ?: SquatRepDetector.DEFAULT_COOLDOWN_MS

        /** End of the last window this shadow accepted; null until one is. */
        private var lastAcceptedEndMs: Long? = null

        override fun process(frame: MotionFrame) = detector.process(frame)
        override val repCount get() = detector.repCount
        override val smoothedMagnitude get() = detector.smoothedMagnitude
        override val label = "SQUAT"

        /** The guards in force, for the log line and the readout. */
        override val guardSummary: String = if (profile == null) {
            "defaults: minDur=${minDur}ms minAmp=$minAmp cooldown=${cooldown}ms " +
                "maxDur=${SquatRepDetector.DEFAULT_MAX_REP_DURATION_MS}ms"
        } else {
            "calibrated: minDur=${minDur}ms minAmp=%.2f cooldown=${cooldown}ms "
                .format(Locale.US, minAmp) +
                "maxDur=${profile.maxRepDurationMs}ms" +
                if (profile.wasClamped) " (CLAMPED)" else ""
        }

        override fun explain(burst: Burst): String {
            if (burst.durationMs < minDur) {
                return "REJECT minRepDurationMs (${burst.durationMs}ms < ${minDur}ms)"
            }

            if (burst.amplitude < minAmp) {
                return String.format(
                    Locale.US, "REJECT minAmplitude (%.3f < %.2f)", burst.amplitude, minAmp,
                )
            }

            val previousEnd = lastAcceptedEndMs
            if (previousEnd != null && burst.startMs - previousEnd < cooldown) {
                return "REJECT cooldownMs (${burst.startMs - previousEnd}ms since last rep " +
                    "< ${cooldown}ms)"
            }

            val gap = previousEnd?.let { "${burst.startMs - it}ms since last rep" } ?: "first rep"
            lastAcceptedEndMs = burst.endMs
            return "ACCEPT rep ($gap)"
        }
    }

    class JumpingJack : ProbeDetector() {
        private val detector = JumpingJackRepDetector()

        /** The jump-out this shadow is holding, mirroring the detector's own pairing state. */
        private var pending: Burst? = null

        override fun process(frame: MotionFrame) = detector.process(frame)
        override val repCount get() = detector.repCount
        override val smoothedMagnitude get() = detector.smoothedMagnitude
        override val awaitingLanding get() = detector.awaitingLanding
        override val label = "JUMPING JACK"
        override val guardSummary =
            "defaults: minBurstAmp=${JumpingJackRepDetector.DEFAULT_MIN_BURST_AMPLITUDE} " +
                "minBurstDur=${JumpingJackRepDetector.DEFAULT_MIN_BURST_DURATION_MS}ms"

        override fun explain(burst: Burst): String {
            val minAmp = JumpingJackRepDetector.DEFAULT_MIN_BURST_AMPLITUDE
            if (burst.amplitude < minAmp) {
                return String.format(
                    Locale.US, "REJECT minBurstAmplitude (%.3f < %.2f) - rebound",
                    burst.amplitude, minAmp,
                )
            }

            val minDur = JumpingJackRepDetector.DEFAULT_MIN_BURST_DURATION_MS
            if (burst.durationMs < minDur) {
                return "REJECT minBurstDurationMs (${burst.durationMs}ms < ${minDur}ms) - rebound"
            }

            val held = pending
            if (held == null) {
                pending = burst
                return String.format(
                    Locale.US, "HOLD jump-out (amp %.3f) - awaiting landing", burst.amplitude,
                )
            }
            if (burst.amplitude <= held.amplitude) {
                pending = burst
                return String.format(
                    Locale.US,
                    "RESYNC not a landing (%.3f <= held %.3f) - held burst discarded",
                    burst.amplitude, held.amplitude,
                )
            }
            pending = null
            return String.format(
                Locale.US, "ACCEPT rep (jump-out %.3f + landing %.3f)",
                held.amplitude, burst.amplitude,
            )
        }
    }
}

/** One smoothed reading, kept only long enough to find a closed window's peak. */
private data class Sample(val tMillis: Long, val smoothed: Float)

/**
 * The probe's readout: which detector is active, a rep count big enough to read from the floor
 * mid-rep, and the few numbers worth glancing at while tuning.
 *
 * Stateless and passed only plain values, so it is the one part of this throwaway that would
 * survive into the real UI.
 */
@Composable
private fun ProbeReadout(
    detectorLabel: String,
    isJumpingJack: Boolean,
    awaitingLanding: Boolean,
    repCount: Int,
    lastRep: RepEvent?,
    smoothed: Float,
    hz: Float,
    bpm: Int,
    metronomeRunning: Boolean,
    guardSummary: String,
    calibrating: Boolean,
    calibrationCaptured: Int,
    calibrationStatus: String?,
    hasProfile: Boolean,
    onSelectSquat: () -> Unit,
    onSelectJumpingJack: () -> Unit,
    onStartCalibration: () -> Unit,
    onClearCalibration: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onToggleMetronome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Detector picker. Two buttons rather than a segmented control: fewer experimental
        // Material3 APIs to pin, and this screen will never be reviewed for polish.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetectorButton("Squat", selected = !isJumpingJack, onClick = onSelectSquat)
            DetectorButton("Jumping jack", selected = isJumpingJack, onClick = onSelectJumpingJack)
        }

        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = detectorLabel,
            fontSize = 20.sp,
        )

        Text(
            text = "$repCount",
            fontSize = 130.sp,
            textAlign = TextAlign.Center,
        )
        Text(text = "REPS", fontSize = 22.sp)

        // Pairing state: the fastest way to read a jumping-jack miscount for what it is.
        if (isJumpingJack) {
            Text(
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 14.sp,
                text = if (awaitingLanding) {
                    "jump-out held — awaiting landing"
                } else {
                    "awaiting jump-out"
                },
            )
        }

        val last = lastRep
        Text(
            modifier = Modifier.padding(top = 20.dp),
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
            modifier = Modifier.padding(top = 12.dp),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            text = String.format(
                Locale.US,
                "smoothed %.2f  (opens at 10.15)\n%.1f Hz",
                smoothed,
                hz,
            ),
        )

        // Metronome. Offered under both detectors -- it does no harm during squats -- but only
        // jumping jacks actually depend on a paced cadence.
        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onBpmChange(-BPM_STEP) }) { Text("−") }
            Button(onClick = onToggleMetronome) {
                Text(if (metronomeRunning) "Stop $bpm BPM" else "Start $bpm BPM")
            }
            OutlinedButton(onClick = { onBpmChange(+BPM_STEP) }) { Text("+") }
        }

        if (isJumpingJack) {
            Text(
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                text = "one beep = one full jack\nvalidated at 45-57 BPM",
            )
        }

        // Calibration. Squats only: CalibrationProfile derives SquatRepDetector thresholds and
        // has nothing to say about jumping jacks.
        if (!isJumpingJack) {
            Row(
                modifier = Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onStartCalibration, enabled = !calibrating) {
                    Text(
                        if (calibrating) {
                            "Calibrating $calibrationCaptured/${CalibrationProfile.REQUIRED_SAMPLES}"
                        } else {
                            "Calibrate"
                        },
                    )
                }
                OutlinedButton(onClick = onClearCalibration, enabled = hasProfile || calibrating) {
                    Text("Use defaults")
                }
            }
            if (calibrating) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    text = "one beep = one squat",
                )
            }
            if (calibrationStatus != null) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    text = calibrationStatus,
                )
            }
        }

        // The thresholds actually counting reps right now, so a screenshot of a miscount says
        // whether it was the defaults or a calibration that produced it.
        Text(
            modifier = Modifier.padding(top = 12.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            text = guardSummary,
        )

        Text(
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            text = "TEMPORARY probe — Logcat tag RepMateProbe",
        )
    }
}

/** Tempo step for the +/- buttons. Top-level so the composable can see it. */
private const val BPM_STEP = 2

@Composable
private fun DetectorButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
