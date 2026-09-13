package com.repmate.data.local

import com.repmate.data.repo.SessionRepository
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSessionRepository @Inject constructor(
    private val sessionDao: SessionDao
) : SessionRepository {

    override suspend fun save(session: WorkoutSession) {
        val sessionEntity = WorkoutSessionEntity(
            id = session.id,
            exercise = session.exercise.name,
            startedAt = session.startedAt
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

        sessionDao.replaceSession(
            session = sessionEntity,
            repScores = repScoreEntities
        )
    }

    override fun recent(limit: Int): Flow<List<WorkoutSession>> {
        require(limit > 0) {
            "limit must be positive"
        }

        return sessionDao.observeRecentSessions(limit).map { sessions ->
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
                    frames = null
                )
            }
        }
    }
}