package com.repmate.data.repo

import com.repmate.engine.ExerciseType

/**
 * The boundary between "has this exercise been calibrated" and however that's actually
 * persisted. Mirrors [SessionRepository]'s reasoning exactly: callers (Home's exercise chips,
 * live_workout's pre-workout check) depend on this interface, not on a Room DAO directly, so the
 * storage decision lives in one place and both call sites move together when it changes.
 *
 * There is deliberately no implementation in this package -- see [SessionRepository]'s doc for
 * why. The current implementation, `com.repmate.data.local.StubCalibrationRepository`, is the
 * "ten-line in-memory fake" that doc describes: calibration profiles have no Room table yet, so
 * it always reports false, which is what correctly routes every exercise through Calibration
 * (skippable there) until a profile can actually be saved and checked for real.
 */
interface CalibrationRepository {
    /** Whether [exerciseType] already has a saved calibration profile for the current user. */
    suspend fun hasProfile(exerciseType: ExerciseType): Boolean
}
