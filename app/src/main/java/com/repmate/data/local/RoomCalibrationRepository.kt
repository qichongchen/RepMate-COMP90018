package com.repmate.data.local

import com.repmate.data.repo.CalibrationRepository
import com.repmate.engine.CalibrationProfile
import com.repmate.engine.ExerciseType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomCalibrationRepository @Inject constructor(
    private val calibrationProfileDao: CalibrationProfileDao
) : CalibrationRepository {

    override suspend fun hasProfile(
        exerciseType: ExerciseType
    ): Boolean {
        return calibrationProfileDao.hasProfile(exerciseType.name)
    }

    override suspend fun saveProfile(
        exerciseType: ExerciseType,
        profile: CalibrationProfile
    ) {
        val entity = CalibrationProfileEntity(
            exercise = exerciseType.name,
            minAmplitude = profile.minAmplitude,
            minRepDurationMs = profile.minRepDurationMs,
            maxRepDurationMs = profile.maxRepDurationMs,
            cooldownMs = profile.cooldownMs,
            sampleCount = profile.sampleCount,
            softestSampleAmplitude = profile.softestSampleAmplitude,
            shortestSampleMs = profile.shortestSampleMs,
            longestSampleMs = profile.longestSampleMs,
            fastestSampleGapMs = profile.fastestSampleGapMs,
            notes = profile.notes.joinToString("\n")
        )

        calibrationProfileDao.insertProfile(entity)
    }

    override suspend fun getProfile(
        exerciseType: ExerciseType
    ): CalibrationProfile? {

        val entity =
            calibrationProfileDao.getProfile(exerciseType.name)
                ?: return null

        return CalibrationProfile(
            minAmplitude = entity.minAmplitude,
            minRepDurationMs = entity.minRepDurationMs,
            maxRepDurationMs = entity.maxRepDurationMs,
            cooldownMs = entity.cooldownMs,
            sampleCount = entity.sampleCount,
            softestSampleAmplitude = entity.softestSampleAmplitude,
            shortestSampleMs = entity.shortestSampleMs,
            longestSampleMs = entity.longestSampleMs,
            fastestSampleGapMs = entity.fastestSampleGapMs,
            notes = entity.notes
                .lines()
                .filter { it.isNotBlank() }
        )
    }
}