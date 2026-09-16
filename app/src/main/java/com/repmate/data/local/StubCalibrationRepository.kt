package com.repmate.data.local

import com.repmate.data.repo.CalibrationRepository
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TODO(data.local): replace with a real Room-backed [CalibrationRepository] once calibration
 * profiles have a table to live in. Until then this is a process-lifetime in-memory map, not
 * Room -- a profile saved here survives navigating away from `CalibrationScreen` and back (so a
 * calibrated exercise correctly skips straight to Live Workout for the rest of the session), but
 * not an app restart, and is not scoped per Firebase UID either.
 */
@Singleton
class StubCalibrationRepository
    @Inject
    constructor() : CalibrationRepository {
        private val profiles = ConcurrentHashMap<ExerciseType, CalibrationProfile>()

        override suspend fun hasProfile(exerciseType: ExerciseType): Boolean = profiles.containsKey(exerciseType)

        override suspend fun saveProfile(
            exerciseType: ExerciseType,
            profile: CalibrationProfile,
        ) {
            profiles[exerciseType] = profile
        }

        override suspend fun getProfile(exerciseType: ExerciseType): CalibrationProfile? = profiles[exerciseType]
    }
