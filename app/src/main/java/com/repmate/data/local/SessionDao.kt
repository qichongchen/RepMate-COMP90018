package com.repmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepScores(repScores: List<RepScoreEntity>)

    @Query(
        """
    SELECT * FROM workout_sessions
    WHERE ownerId = :ownerId
    ORDER BY startedAt DESC
    LIMIT :limit
    """
    )
    fun observeRecentSessions(
        ownerId: String,
        limit: Int
    ): Flow<List<WorkoutSessionEntity>>

    @Query(
        """
        SELECT * FROM workout_sessions
        WHERE id = :id AND ownerId = :ownerId
        LIMIT 1
        """
    )
    suspend fun getSessionById(
        id: String,
        ownerId: String
    ): WorkoutSessionEntity?

    @Query(
        """
        SELECT * FROM rep_scores
        WHERE sessionId = :sessionId
        ORDER BY repIndex ASC
        """
    )
    suspend fun getRepScores(sessionId: String): List<RepScoreEntity>

    @Query("DELETE FROM rep_scores WHERE sessionId = :sessionId")
    suspend fun deleteRepScores(sessionId: String)

    @Transaction
    suspend fun replaceSession(
        session: WorkoutSessionEntity,
        repScores: List<RepScoreEntity>
    ) {
        insertSession(session)
        deleteRepScores(session.id)

        if (repScores.isNotEmpty()) {
            insertRepScores(repScores)
        }
    }

    @Transaction
    suspend fun insertSessionIfMissing(
        session: WorkoutSessionEntity,
        repScores: List<RepScoreEntity>
    ): Boolean {
        val existing = getSessionById(
            id = session.id,
            ownerId = session.ownerId
        )

        if (existing != null) {
            return false
        }

        insertSession(session)

        if (repScores.isNotEmpty()) {
            insertRepScores(repScores)
        }

        return true
    }
}