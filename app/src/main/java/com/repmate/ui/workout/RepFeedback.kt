package com.repmate.ui.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * The two per-rep side effects the workout and calibration ViewModels trigger on a detected rep:
 * a short haptic buzz, and the new rep count spoken aloud. Each is gated independently by the
 * user's "Haptic feedback"/"Spoken rep count" settings on the Profile screen
 * ([com.repmate.ui.theme.WorkoutPreferences]), which the caller passes in per call rather than
 * this class reading them itself -- keeps this a plain side-effect wrapper with no coroutine or
 * DataStore dependency. Neither effect is persistent UI state -- the
 * "buzz on rep"/"beep count" indicators on [LiveWorkoutScreen] are static labels, not driven by
 * a field on [LiveWorkoutUiState], since a fired-or-not flag for a one-shot effect isn't state
 * worth modelling.
 *
 * `TextToSpeech` initialises asynchronously and must be shut down when done, and `Vibrator` is a
 * real device service -- both are genuine Android dependencies, which is why this lives in the
 * UI layer rather than alongside the (pure Kotlin) engine classes it's reacting to.
 *
 * One instance per workout: constructed once by the owning ViewModel, [release]d in
 * `onCleared()`.
 */
class RepFeedback(context: Context) {
    private val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java)
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechReady = false

    init {
        textToSpeech =
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.getDefault()
                    isTextToSpeechReady = true
                }
            }
    }

    /**
     * Called once, right after a rep is scored. Buzzes if [hapticFeedbackEnabled], and speaks
     * [repCount] if [spokenRepCountEnabled] -- the two are independent, so either, both, or
     * neither may fire.
     */
    fun onRepDetected(
        repCount: Int,
        hapticFeedbackEnabled: Boolean,
        spokenRepCountEnabled: Boolean,
    ) {
        if (hapticFeedbackEnabled) {
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(BUZZ_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
        // Silently does nothing if TextToSpeech never finished initialising (no engine installed,
        // still loading) -- a missing rep count announcement must not crash a workout in progress,
        // same degrade-gracefully reasoning as everywhere else sensor/platform state is optional.
        if (spokenRepCountEnabled && isTextToSpeechReady) {
            textToSpeech?.speak(repCount.toString(), TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    companion object {
        private const val BUZZ_DURATION_MS = 60L
    }
}
