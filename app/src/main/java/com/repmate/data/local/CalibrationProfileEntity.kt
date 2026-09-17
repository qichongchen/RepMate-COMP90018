package com.repmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calibration_profiles")
data class CalibrationProfileEntity(
    @PrimaryKey
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