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

    @Query(
        """
    SELECT
        AVG(rs.score) AS averageScore,
        COUNT(*) AS repCount
    FROM workout_sessions AS ws
    INNER JOIN rep_scores AS rs
        ON rs.sessionId = ws.id
    WHERE ws.ownerId = :ownerId
      AND ws.exercise = :exercise
    GROUP BY ws.id
    ORDER BY averageScore DESC,
             repCount DESC,
             ws.startedAt DESC
    LIMIT 1
    """
    )
    suspend fun getBestSessionScore(
        ownerId: String,
        exercise: String
    ): BestSessionResult?

    /** Every session stored under [ownerId], oldest first. Used to read a replaced guest's history. */
    @Query("SELECT * FROM workout_sessions WHERE ownerId = :ownerId ORDER BY startedAt ASC")
    suspend fun getSessionsByOwner(ownerId: String): List<WorkoutSessionEntity>

    /**
     * Hands sessions from one owner to another by rewriting only `ownerId`. Keyed on both the id
     * and the old owner, so it can only move rows still owned by [oldOwnerId] and is a no-op when
     * repeated. The rep rows reference the session id, which is unchanged, so they are untouched.
     *
     * @return the number of sessions actually moved.
     */
    @Query("UPDATE workout_sessions SET ownerId = :newOwnerId WHERE id IN (:ids) AND ownerId = :oldOwnerId")
    suspend fun reassignOwner(
        ids: List<String>,
        oldOwnerId: String,
        newOwnerId: String
    ): Int

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