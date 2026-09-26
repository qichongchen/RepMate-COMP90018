package com.repmate.data.cloud

import com.repmate.data.local.RepScoreEntity
import com.repmate.data.local.WorkoutSessionEntity

data class FirestoreWorkoutSession(
    val id: String,
    val userId: String,
    val exercise: String,
    val startedAt: Long,
    val endedAt: Long?,
    val reps: List<FirestoreRepScore>,
)

data class FirestoreRepScore(
    val repIndex: Int,
    val score: Int,
    val tempoSeconds: Double,
    val rangePercent: Double,
    val pauseSeconds: Double,
    val reasons: List<String>,
)

fun FirestoreWorkoutSession.toRoomSession(
    ownerId: String
): WorkoutSessionEntity {
    return WorkoutSessionEntity(
        id = id,
        ownerId = ownerId,
        exercise = exercise,
        startedAt = startedAt,
        endedAt = endedAt
    )
}

fun FirestoreWorkoutSession.toRoomRepScores(): List<RepScoreEntity> {
    return reps.map { rep ->
        RepScoreEntity(
            sessionId = id,
            repIndex = rep.repIndex,
            score = rep.score.toFloat(),
            tempoSeconds = rep.tempoSeconds.toFloat(),
            rangePercent = rep.rangePercent.toInt(),
            pauseSeconds = rep.pauseSeconds.toFloat(),
            reasons = rep.reasons.joinToString("|")
        )
    }
}