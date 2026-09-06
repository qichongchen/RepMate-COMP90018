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