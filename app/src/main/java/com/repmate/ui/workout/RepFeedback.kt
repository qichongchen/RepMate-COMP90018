package com.repmate.ui.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * The two real, per-rep side effects [LiveWorkoutViewModel] triggers on every detected rep: a
 * short haptic buzz, and the new rep count spoken aloud. Neither is persistent UI state -- the
 * "buzz on rep"/"beep count" indicators on [LiveWorkoutScreen] are static labels, not driven by
 * a field on [LiveWorkoutUiState], since a fired-or-not flag for a one-shot effect isn't state
 * worth modelling.
 *
 * `TextToSpeech` initialises asynchronously and must be shut down when done, and `Vibrator` is a
 * real device service -- both are genuine Android dependencies, which is why this lives in the
 * UI layer rather than alongside the (pure Kotlin) engine classes it's reacting to.
 *
 * One instance per workout: constructed once by [LiveWorkoutViewModel], [release]d in
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

    /** Buzzes and speaks [repCount] -- called once, right after a rep is scored. */
    fun onRepDetected(repCount: Int) {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(BUZZ_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        // Silently does nothing if TextToSpeech never finished initialising (no engine installed,
        // still loading) -- a missing rep count announcement must not crash a workout in progress,
        // same degrade-gracefully reasoning as everywhere else sensor/platform state is optional.
        if (isTextToSpeechReady) {
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
