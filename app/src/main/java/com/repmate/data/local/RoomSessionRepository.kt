package com.repmate.data.local

import com.repmate.data.repo.SessionRepository
import com.repmate.data.repo.BestWorkoutScore
import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton
import android.database.sqlite.SQLiteException
import android.util.Log

@Singleton
class RoomSessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val authRepository: AuthRepository
) : SessionRepository {

    override suspend fun getById(id: String): WorkoutSession? {
        val ownerId = authRepository.getCurrentUserId()
            ?: return null

        val sessionEntity = sessionDao.getSessionById(
            id = id,
            ownerId = ownerId
        ) ?: return null

        val reps = sessionDao
            .getRepScores(sessionEntity.id)
            .map { repEntity ->
                RepScore(
                    repIndex = repEntity.repIndex,
                    score = repEntity.score,
                    tempoSeconds = repEntity.tempoSeconds,
                    rangePercent = repEntity.rangePercent,
                    pauseSeconds = repEntity.pauseSeconds,
                    reasons = repEntity.reasons
                        .split("|")
                        .filter { it.isNotBlank() }
                )
            }

        return WorkoutSession(
            id = sessionEntity.id,
            exercise = ExerciseType.valueOf(sessionEntity.exercise),
            startedAt = sessionEntity.startedAt,
            reps = reps,
            frames = null,
            endedAt = sessionEntity.endedAt
        )
    }

    override suspend fun save(session: WorkoutSession) {
        val ownerId = authRepository.getCurrentUserId()
            ?: return

        val sessionEntity = WorkoutSessionEntity(
            id = session.id,
            ownerId = ownerId,
            exercise = session.exercise.name,
            startedAt = session.startedAt,
            endedAt = session.endedAt
        )

        val repScoreEntities = session.reps.map { rep ->
            RepScoreEntity(
                sessionId = session.id,
                repIndex = rep.repIndex,
                score = rep.score,
                tempoSeconds = rep.tempoSeconds,
                rangePercent = rep.rangePercent,
                pauseSeconds = rep.pauseSeconds,
                reasons = rep.reasons.joinToString("|")
            )
        }

        try {
            sessionDao.replaceSession(
                session = sessionEntity,
                repScores = repScoreEntities
            )
        } catch (e: SQLiteException) {
            Log.e(
                "RoomSessionRepository",
                "Failed to save session ${session.id}",
                e
            )
        }
    }

    override fun recent(limit: Int): Flow<List<WorkoutSession>> {
        require(limit > 0) {
            "limit must be positive"
        }

        val ownerId = authRepository.getCurrentUserId()
            ?: return flowOf(emptyList())

        return sessionDao
            .observeRecentSessions(
                ownerId = ownerId,
                limit = limit
            )
            .map { sessions ->
                sessions.map { sessionEntity ->

                    val reps = sessionDao
                        .getRepScores(sessionEntity.id)
                        .map { repEntity ->
                            RepScore(
                                repIndex = repEntity.repIndex,
                                score = repEntity.score,
                                tempoSeconds = repEntity.tempoSeconds,
                                rangePercent = repEntity.rangePercent,
                                pauseSeconds = repEntity.pauseSeconds,
                                reasons = repEntity.reasons
                                    .split("|")
                                    .filter { it.isNotBlank() }
                            )
                        }

                    WorkoutSession(
                        id = sessionEntity.id,
                        exercise = ExerciseType.valueOf(sessionEntity.exercise),
                        startedAt = sessionEntity.startedAt,
                        reps = reps,
                        frames = null,
                        endedAt = sessionEntity.endedAt
                    )
                }
        }
    }

    override suspend fun getMyBestScore(
        exercise: ExerciseType
    ): BestWorkoutScore? {
        val ownerId = authRepository.getCurrentUserId()
            ?: return null

        val result = sessionDao.getBestSessionScore(
            ownerId = ownerId,
            exercise = exercise.name
        ) ?: return null

        return BestWorkoutScore(
            score = result.averageScore,
            reps = result.repCount
        )
    }
}