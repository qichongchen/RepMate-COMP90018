package com.repmate.data.sync

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.cloud.FirestoreWorkoutDataSource
import com.repmate.data.local.RoomSessionRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.data.cloud.toRoomRepScores
import com.repmate.data.cloud.toRoomSession
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncingSessionRepository @Inject constructor(
    private val roomRepository: RoomSessionRepository,
    private val firestoreWorkoutDataSource: FirestoreWorkoutDataSource,
    private val authRepository: AuthRepository,
) : SessionRepository {

    private companion object {
        const val TAG = "SyncingSessionRepo"
    }

    override suspend fun save(session: WorkoutSession) {
        // Local storage remains the primary/offline copy.
        roomRepository.save(session)

        // Then try to sync the completed workout to Firestore.
        // A cloud failure must not prevent the workout from finishing.
        val result = firestoreWorkoutDataSource.upload(session)

        result.onFailure { error ->
            Log.w(
                TAG,
                "Workout ${session.id} saved locally but Firestore sync failed",
                error
            )
        }
    }

    override fun recent(limit: Int): Flow<List<WorkoutSession>> =
        roomRepository.recent(limit)

    override suspend fun getById(id: String): WorkoutSession? =
        roomRepository.getById(id)

    suspend fun restoreMissingSessions(): Result<Int> {
        val userId = authRepository.getCurrentUserId()
            ?: return Result.failure(
                IllegalStateException("No authenticated user")
            )

        val cloudSessions = firestoreWorkoutDataSource
            .fetchWorkoutSessions(userId)
            .getOrElse { error ->
                Log.w(
                    TAG,
                    "Failed to fetch Firestore sessions for restore",
                    error
                )
                return Result.failure(error)
            }

        var restoredCount = 0

        cloudSessions.forEach { cloudSession ->
            val inserted = roomRepository.insertIfMissing(
                session = cloudSession.toRoomSession(
                    ownerId = userId
                ),
                repScores = cloudSession.toRoomRepScores()
            )

            if (inserted) {
                restoredCount++
            }
        }

        Log.d(
            TAG,
            "Restored $restoredCount missing workout sessions"
        )

        return Result.success(restoredCount)
    }
}