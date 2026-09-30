package com.repmate.data.local

import com.repmate.data.sync.GuestSessionStore
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [GuestSessionStore]: reads and re-owns another owner's sessions straight from the DAO. */
@Singleton
class RoomGuestSessionStore
    @Inject
    constructor(
        private val sessionDao: SessionDao,
    ) : GuestSessionStore {
        override suspend fun sessionsOwnedBy(ownerId: String): List<WorkoutSession> =
            sessionDao.getSessionsByOwner(ownerId).mapNotNull { entity ->
                // An exercise name this build doesn't know is skipped rather than crashing the merge.
                val exercise = ExerciseType.entries.firstOrNull { it.name == entity.exercise } ?: return@mapNotNull null
                WorkoutSession(
                    id = entity.id,
                    exercise = exercise,
                    startedAt = entity.startedAt,
                    reps =
                        sessionDao.getRepScores(entity.id).map { rep ->
                            RepScore(
                                repIndex = rep.repIndex,
                                score = rep.score,
                                tempoSeconds = rep.tempoSeconds,
                                rangePercent = rep.rangePercent,
                                pauseSeconds = rep.pauseSeconds,
                                reasons = rep.reasons.split("|").filter { it.isNotBlank() },
                            )
                        },
                    frames = null,
                    endedAt = entity.endedAt,
                )
            }

        override suspend fun reassignOwner(
            sessionIds: List<String>,
            fromOwnerId: String,
            toOwnerId: String,
        ) {
            // Chunked to stay under SQLite's bound-variable limit on a very long history. Each chunk
            // is idempotent, so a crash between chunks just leaves the rest for the retry.
            sessionIds.chunked(REASSIGN_CHUNK_SIZE).forEach { chunk ->
                sessionDao.reassignOwner(chunk, fromOwnerId, toOwnerId)
            }
        }

        private companion object {
            const val REASSIGN_CHUNK_SIZE = 500
        }
    }
