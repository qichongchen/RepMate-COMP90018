package com.repmate.ui.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.repmate.engine.JumpingJackRepDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Plays an audible beat at [JumpingJackRepDetector.TARGET_BPM] for as long as it's running --
 * the audio half of the paced cadence [JumpingJackRepDetector]'s whole counting model assumes
 * (see "This detector assumes a paced cadence" in that class's KDoc). Shared between
 * `CalibrationScreen`'s jumping-jack recording state and `LiveWorkoutScreen`'s jumping-jack
 * workouts -- both read the exact same [JumpingJackRepDetector.TARGET_BPM] constant for their
 * visual pulse too, so audio and visual stay in sync everywhere by construction rather than by
 * hand-tuned copies of the same number drifting apart. Lives in `ui.audio` rather than either
 * screen's own package precisely because both need it.
 *
 * There is no bundled beep sample in the project yet, so the click played on each beat is
 * generated once per instance (a short sine tone written out as a small WAV file in the app's
 * cache dir -- see [generateBeepFile]) rather than requiring one. `SoundPool` loads from a
 * file/resource, not a raw in-memory buffer, which is why this goes via a cache file instead of
 * handing it samples directly.
 *
 * One instance per screen visit: [start] creates the [SoundPool] and begins the beat loop,
 * [release] tears both down. Not reusable after [release] -- construct a new instance for the
 * next visit (this is what each caller's `DisposableEffect` does).
 */
class JumpingJackMetronome(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var soundPool: SoundPool? = null
    private var beatJob: Job? = null

    fun start() {
        val pool =
            SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // A timing cue, not media content -- same category Android expects for a
                        // keypress tone or a camera shutter click.
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
        soundPool = pool

        // load() is asynchronous; starting the beat loop only once loading actually completes
        // avoids the first beat silently doing nothing because the sample wasn't ready yet.
        pool.setOnLoadCompleteListener { _, loadedSoundId, status ->
            if (status == 0) {
                beatJob = scope.launch { beatLoop(pool, loadedSoundId) }
            }
        }
        pool.load(generateBeepFile(context).absolutePath, 1)
    }

    private suspend fun beatLoop(
        pool: SoundPool,
        soundId: Int,
    ) {
        val intervalMs = 60_000L / JumpingJackRepDetector.TARGET_BPM
        while (true) {
            pool.play(soundId, 1f, 1f, 1, 0, 1f)
            delay(intervalMs)
        }
    }

    fun release() {
        beatJob?.cancel()
        scope.cancel()
        soundPool?.release()
        soundPool = null
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 44_100
        private const val BEEP_DURATION_MS = 80
        private const val BEEP_FREQUENCY_HZ = 880.0
        private const val BEEP_FILE_NAME = "jumping_jack_beep.wav"

        /**
         * Generates a short sine-wave click and writes it out as a WAV file in the app's cache
         * dir. Regenerated on every [start] rather than cached across visits: the file is a few
         * KB and writing it takes a handful of milliseconds, not worth the added state to skip.
         */
        private fun generateBeepFile(context: Context): File {
            val sampleCount = SAMPLE_RATE_HZ * BEEP_DURATION_MS / 1000
            val fadeSamples = sampleCount / 4
            val samples =
                ShortArray(sampleCount) { i ->
                    val angle = 2.0 * Math.PI * BEEP_FREQUENCY_HZ * i / SAMPLE_RATE_HZ
                    // Fades the last quarter out linearly so the clip doesn't end on an audible
                    // click from a sudden amplitude discontinuity.
                    val samplesFromEnd = sampleCount - i
                    val fade = if (samplesFromEnd < fadeSamples) samplesFromEnd.toDouble() / fadeSamples else 1.0
                    (sin(angle) * fade * Short.MAX_VALUE).toInt().toShort()
                }
            val file = File(context.cacheDir, BEEP_FILE_NAME)
            FileOutputStream(file).use { out ->
                out.write(wavHeader(dataSize = samples.size * 2))
                out.write(pcmBytes(samples))
            }
            return file
        }

        /** Standard 44-byte canonical PCM WAV header: mono, 16-bit, [SAMPLE_RATE_HZ]. */
        private fun wavHeader(dataSize: Int): ByteArray {
            val byteRate = SAMPLE_RATE_HZ * 2
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // fmt chunk size
                putShort(1) // PCM
                putShort(1) // mono
                putInt(SAMPLE_RATE_HZ)
                putInt(byteRate)
                putShort(2) // block align (mono, 16-bit)
                putShort(16) // bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }.array()
        }

        private fun pcmBytes(samples: ShortArray): ByteArray =
            ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                samples.forEach { putShort(it) }
            }.array()
    }
}
