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
    val score: Float,
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
            score = rep.score,
            tempoSeconds = rep.tempoSeconds.toFloat(),
            rangePercent = rep.rangePercent.toInt(),
            pauseSeconds = rep.pauseSeconds.toFloat(),
            reasons = rep.reasons.joinToString("|")
        )
    }
}

/**
 * Converts one raw rep map, as Firestore hands it back inside a workout document's `reps` list,
 * into a [FirestoreRepScore]. Pure (no snapshot needed) so it can be unit tested.
 *
 * Returns null, so the caller skips the rep, when [rawRep] is not a map or has no numeric
 * `repIndex` or `score`. The other numeric fields fall back to 0 and `reasons` to empty.
 *
 * `score` is read as a [Float] through [Number]: the upload writes a Float, which Firestore
 * stores as a double, so reading it with `toInt()` would round every restored score to a whole
 * number. Going through [Number] also still accepts a whole-number score (stored as a long) from
 * an older document, and a Float widened to double and narrowed back is exact.
 */
internal fun parseFirestoreRepScore(rawRep: Any?): FirestoreRepScore? {
    val repMap = rawRep as? Map<*, *> ?: return null
    val repIndex = (repMap["repIndex"] as? Number)?.toInt() ?: return null
    val score = (repMap["score"] as? Number)?.toFloat() ?: return null

    return FirestoreRepScore(
        repIndex = repIndex,
        score = score,
        tempoSeconds = (repMap["tempoSeconds"] as? Number)?.toDouble() ?: 0.0,
        rangePercent = (repMap["rangePercent"] as? Number)?.toDouble() ?: 0.0,
        pauseSeconds = (repMap["pauseSeconds"] as? Number)?.toDouble() ?: 0.0,
        reasons = (repMap["reasons"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
    )
}
