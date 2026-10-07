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
 * - **Squats only.** [com.repmate.engine.SessionReplayer] re-derives rep windows with
 *   [com.repmate.engine.SquatRepDetector] specifically, so handing it a jumping-jack session
 *   would produce a curve cut up by the wrong detector, and a push-up session carries no body
 *   motion at all (the phone is deliberately still -- see `PhoneStabilityGate`). Both keep the
 *   no-replay notice instead of a misleading picture. [remember] enforces this rather than
 *   trusting callers.
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

        /**
         * Remembers [session] as the one to replay, replacing whatever was held.
         *
         * Ignored unless the session is a squat with frames, so the caller cannot accidentally
         * make the replay screen draw a curve derived by the wrong detector.
         */
        fun remember(session: WorkoutSession) {
            if (session.exercise != ExerciseType.SQUAT) return
            if (session.frames.isNullOrEmpty()) return
            stored = session
        }

        /** The stored session if it is the one [sessionId] names, else null. Does not consume it. */
        fun sessionFor(sessionId: String): WorkoutSession? = stored?.takeIf { it.id == sessionId }

        /** Drops the stored session and its frames. Called when a new workout starts. */
        fun clear() {
            stored = null
        }
    }
