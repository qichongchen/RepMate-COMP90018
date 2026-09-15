package com.repmate.data.local

import com.repmate.data.repo.CalibrationRepository
import com.repmate.engine.ExerciseType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TODO(data.local): replace with a real Room-backed [CalibrationRepository] once calibration
 * profiles have a table to live in. Until then this always reports false for every exercise --
 * intentional, not an oversight: it's what makes every exercise chip and live_workout entry
 * correctly route through Calibration (skippable there, per the engine team's spec) rather than
 * silently skipping a step that hasn't actually happened yet.
 */
@Singleton
class StubCalibrationRepository
    @Inject
    constructor() : CalibrationRepository {
        override suspend fun hasProfile(exerciseType: ExerciseType): Boolean = false
    }
