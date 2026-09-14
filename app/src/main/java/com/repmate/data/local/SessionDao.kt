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
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentSessions(limit: Int): Flow<List<WorkoutSessionEntity>>

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
}