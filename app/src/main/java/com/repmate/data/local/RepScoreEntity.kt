package com.repmate.data.local

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "rep_scores",
    primaryKeys = ["sessionId", "repIndex"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RepScoreEntity(
    val sessionId: String,
    val repIndex: Int,
    val score: Float,
    val tempoSeconds: Float,
    val rangePercent: Int,
    val pauseSeconds: Float,
    val reasons: String
)