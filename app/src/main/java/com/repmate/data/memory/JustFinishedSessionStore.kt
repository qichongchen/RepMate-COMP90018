package com.repmate.data.memory

import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the workout session the user **just finished**, with its captured
 * [WorkoutSession.frames], so Motion Replay can draw the real curve for it.
 *
 * ## Why this exists at all
 * `workout_sessions` has no column for frames and [com.repmate.data.repo.SessionRepository] saves
 * every session with `frames = null`, so a session loaded from Room can never be replayed --
 * [com.repmate.ui.motionreplay.MotionReplayViewModel] always fell back to its "no replay data"
 * state. Adding a frames column would mean a schema migration and storing megabytes of sensor
 * data per workout, for a screen the user opens once, right after the set.
 *
 * So the frames are never persisted. They live here, in process memory, for exactly as long as
 * they are useful: from the end of a set until the next one starts.
 *
 * ## What it holds, and for how long
 * - **One slot.** Only the latest finished session. Reviewing an older session from History still
 *   shows the honest no-replay notice, which is the documented scope: *just-finished* replay only.
 * - **The two IMU exercises only.** [com.repmate.engine.SessionReplayer] re-derives rep windows
 *   with the detector that counted the set, and both the squat and jumping-jack detectors read
 *   the same smoothed acceleration magnitude, so one curve serves both. A push-up set carries no
 *   body motion at all -- it is counted from the camera and the phone is deliberately held still
 *   (see `PhoneStabilityGate`) -- so it keeps the no-replay notice. [canReplay] is the single
 *   place that decides, and [remember] enforces it rather than trusting callers.
 * - **Cleared when the next workout starts**, so a set's frames are not held for the life of the
 *   process. Surviving process death is explicitly not a goal: the session is in Room either way,
 *   it just replays without a curve.
 *
 * Reads and writes both happen on the main dispatcher (a ViewModel ending a workout, a ViewModel
 * opening the replay screen), but the field is [Volatile] so a write is still visible to a reader
 * that happens to be on another thread.
 */
@Singleton
class JustFinishedSessionStore
    @Inject
    constructor() {
        @Volatile
        private var stored: WorkoutSession? = null

        @Volatile
        private var pushupTraces: Pair<String, List<PushupRepTrace>>? = null

        /**
         * Remembers [session] as the one to replay, replacing whatever was held.
         *
         * Ignored unless the exercise is one [canReplay] accepts and the session actually carries
         * frames, so a caller cannot make the replay screen draw a curve the replayer could not
         * have produced.
         */
        fun remember(session: WorkoutSession) {
            if (!canReplay(session.exercise)) return
            if (session.frames.isNullOrEmpty()) return
            stored = session
        }

        /** The stored session if it is the one [sessionId] names, else null. Does not consume it. */
        fun sessionFor(sessionId: String): WorkoutSession? = stored?.takeIf { it.id == sessionId }

        /**
         * Remembers the per-rep elbow-angle curves of the push-up set [sessionId], the push-up
         * equivalent of the frames kept for the IMU exercises.
         *
         * Push-ups get their own slot because their movement is not in the accelerometer at all:
         * the phone is propped up and held still, so there are no frames to replay. The angles are
         * collected as the set happens (see `PushupAngleTrace`). Empty traces are ignored -- there
         * would be nothing to draw.
         */
        fun rememberPushupTraces(
            sessionId: String,
            reps: List<PushupRepTrace>,
        ) {
            if (reps.isEmpty()) return
            pushupTraces = sessionId to reps
        }

        /** The stored angle curves if they belong to [sessionId], else null. */
        fun pushupTracesFor(sessionId: String): List<PushupRepTrace>? =
            pushupTraces?.takeIf { it.first == sessionId }?.second

        /**
         * Drops everything held for the last set -- the session's frames and any push-up angle
         * curves. Called when a new workout starts.
         */
        fun clear() {
            stored = null
            pushupTraces = null
        }

        companion object {
            /**
             * Whether a finished [exerciseType] can be replayed from its frames.
             *
             * True for the IMU exercises, false for [ExerciseType.PUSHUP], which is counted from
             * the camera: its recorded frames describe a stationary propped-up phone, not the
             * movement. Keeping the rule here means the capture in `LiveWorkoutViewModel`, the
             * store and `SessionReplayer` cannot disagree about it.
             */
            fun canReplay(exerciseType: ExerciseType): Boolean = exerciseType != ExerciseType.PUSHUP
        }
    }
