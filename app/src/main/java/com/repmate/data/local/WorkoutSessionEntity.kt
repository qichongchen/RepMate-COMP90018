package com.repmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey
    val id: String,
    val ownerId: String,
    val exercise: String,
    val startedAt: Long,
    val endedAt: Long? = null
)