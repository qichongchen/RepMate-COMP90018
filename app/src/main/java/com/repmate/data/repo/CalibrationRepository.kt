package com.repmate.data.repo

import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType

/**
 * The boundary between "has this exercise been calibrated" (and what its profile is) and however
 * that's actually persisted. Mirrors [SessionRepository]'s reasoning exactly: callers (Home's
 * exercise chips, live_workout's pre-workout check, `CalibrationScreen`) depend on this interface,
 * not on a Room DAO directly, so the storage decision lives in one place and every call site moves
 * together when it changes.
 *
 * There is deliberately no implementation in this package -- see [SessionRepository]'s doc for
 * why. The current implementation, `com.repmate.data.local.StubCalibrationRepository`, is the
 * "ten-line in-memory fake" that doc describes: calibration profiles have no Room table yet, so
 * profiles saved here live only for the process's lifetime, not across an app restart.
 */
interface CalibrationRepository {
    /** Whether [exerciseType] already has a saved calibration profile for the current user. */
    suspend fun hasProfile(exerciseType: ExerciseType): Boolean

    /** Persists [profile] for [exerciseType], overwriting whatever was saved for it before. */
    suspend fun saveProfile(
        exerciseType: ExerciseType,
        profile: CalibrationProfile,
    )

    /**
     * The saved profile for [exerciseType], or `null` if uncalibrated. `null` is not an error
     * case here -- every real detector and [com.repmate.engine.FormScorer] already fall back to
     * tuned defaults / calibration-optional scoring for it, per those classes' own docs.
     */
    suspend fun getProfile(exerciseType: ExerciseType): CalibrationProfile?
}
