package com.repmate.data.local

import androidx.room.Entity

@Entity(
    tableName = "calibration_profiles",
    primaryKeys = ["ownerId", "exercise"]
)

data class CalibrationProfileEntity(
    val ownerId: String,
    val exercise: String,

    val minAmplitude: Float,
    val minRepDurationMs: Long,
    val maxRepDurationMs: Long,
    val cooldownMs: Long,

    val sampleCount: Int,
    val softestSampleAmplitude: Float,
    val shortestSampleMs: Long,
    val longestSampleMs: Long,
    val fastestSampleGapMs: Long,

    val notes: String
)