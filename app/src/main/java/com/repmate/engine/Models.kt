package com.repmate.engine

/** One instant of motion, sampled from the accelerometer and gyroscope (~50 Hz). */
data class MotionFrame(
    val tMillis: Long,
    val ax: Float, val ay: Float, val az: Float,   // accelerometer x/y/z
    val gx: Float, val gy: Float, val gz: Float     // gyroscope x/y/z
)

/** Which exercise a workout is tracking. */
enum class ExerciseType { SQUAT, PUSHUP, JUMPING_JACK }

/** The phase of a single rep's movement cycle, tracked by the detector's state machine. */
enum class RepPhase { IDLE, DESCENDING, BOTTOM, ASCENDING, TOP }

/** One detected repetition: when it started/ended and how large the motion was. */
data class RepEvent(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val amplitude: Float
)

/** Which of [SquatRepDetector]'s guards a movement window failed. */
enum class RejectionGuard {
    /** Open for less than `minRepDurationMs`. */
    TOO_SHORT,

    /** Peak-to-peak swing under `minAmplitude`. */
    TOO_SMALL,

    /** Started less than `cooldownMs` after the last counted rep ended. */
    TOO_SOON_AFTER_LAST_REP,

    /** Stayed open past `maxRepDurationMs` and was abandoned mid-flight. */
    ABANDONED_TOO_LONG,
}

/** One guard a window failed: what was measured against the limit it had to meet. */
data class GuardFailure(val guard: RejectionGuard, val measured: Double, val limit: Double)

/**
 * A movement window the detector opened and then discarded, with **every** guard it failed
 * rather than just the first. Reported through `SquatRepDetector.onRejectedWindow` so a missed
 * rep can be traced to the threshold that dropped it, and to how far over that threshold it was.
 *
 * Windows that never crossed the trigger threshold are not reported: no window was opened.
 */
data class RejectedWindow(
    val startMs: Long,
    val endMs: Long,
    val amplitude: Float,
    val failures: List<GuardFailure>
) {
    val durationMs: Long get() = endMs - startMs

    /** e.g. `duration 240 ms, amplitude 1.62 | TOO_SHORT (240 < 280)`. */
    fun describe(): String = "duration $durationMs ms, amplitude %.2f | ".format(amplitude) +
        failures.joinToString("; ") { f ->
            val op = if (f.guard == RejectionGuard.ABANDONED_TOO_LONG) ">" else "<"
            "${f.guard} (%.2f $op %.2f)".format(f.measured, f.limit)
        }
}

/** A graded rep: a 0–10 score plus the measurements and reasons behind it. */
data class RepScore(
    val repIndex: Int,
    val score: Float,            // 0–10
    val tempoSeconds: Float,
    val rangePercent: Int,
    val pauseSeconds: Float,
    val reasons: List<String>    // e.g. "good depth", "slightly rushed"
)

/** A whole workout: the exercise, when it started, the scored reps, and optionally the raw frames (kept for replay). */
data class WorkoutSession(
    val id: String,
    val exercise: ExerciseType,
    val startedAt: Long,
    val reps: List<RepScore>,
    val frames: List<MotionFrame>? = null
)