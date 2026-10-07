package com.repmate.data.sync

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.cloud.FirestoreWorkoutDataSource
import com.repmate.data.local.RoomSessionRepository
import com.repmate.data.repo.BestWorkoutScore
import com.repmate.data.repo.SessionRepository
import com.repmate.data.cloud.toRoomRepScores
import com.repmate.data.cloud.toRoomSession
import com.repmate.di.ApplicationScope
import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncingSessionRepository @Inject constructor(
    private val roomRepository: RoomSessionRepository,
    private val firestoreWorkoutDataSource: FirestoreWorkoutDataSource,
    private val authRepository: AuthRepository,
    private val localBestScoreSync: LocalBestScoreSync,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : SessionRepository {

    private companion object {
        const val TAG = "SyncingSessionRepo"
    }

    override suspend fun save(session: WorkoutSession) {
        // Local storage remains the primary/offline copy.
        roomRepository.save(session)

        // Then try to sync the completed workout to Firestore.
        // A cloud failure must not prevent the workout from finishing.
        val uploadResult = firestoreWorkoutDataSource.upload(session)

        uploadResult.exceptionOrNull()?.let { error ->
            Log.w(
                TAG,
                "Workout ${session.id} saved locally but Firestore sync failed",
                error
            )
        }

        // The only place a Ghost Duel score is published after a workout. It publishes the best
        // *stored* session of each exercise, which includes the one just saved, and skips guests,
        // so it covers what a publish of just this session used to and also catches up workouts
        // finished offline. It runs whether or not the upload above worked. Launched in the
        // application scope so the workout-finished screen does not wait on up to three Firestore
        // round trips; it is best-effort and must never fail or slow save().
        applicationScope.launch { republishLocalBestScores() }
    }

    override fun recent(limit: Int): Flow<List<WorkoutSession>> =
        roomRepository.recent(limit)

    override suspend fun getById(id: String): WorkoutSession? =
        roomRepository.getById(id)

    override suspend fun getMyBestScore(
        exercise: ExerciseType
    ): BestWorkoutScore? =
        roomRepository.getMyBestScore(exercise)

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

        Log.d(
            TAG,
            "Fetched ${cloudSessions.size} sessions from Firestore: " +
                    cloudSessions.map { it.id }
        )
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

            Log.d(
                TAG,
                "Session ${cloudSession.id}: inserted=$inserted"
            )
        }

        Log.d(
            TAG,
            "Restored $restoredCount missing workout sessions"
        )

        // Restored sessions can be unpublished too, and so can ones finished offline earlier.
        // Inline (not launched): the caller already runs restore in the background.
        republishLocalBestScores()

        return Result.success(restoredCount)
    }

    /**
     * Publishes the best local session of each exercise (see [LocalBestScoreSync]) and logs what
     * fails. Never throws except for cancellation, so neither save() nor restore can be broken by it.
     */
    private suspend fun republishLocalBestScores() {
        try {
            localBestScoreSync.publishAll().forEach { (exercise, error) ->
                Log.w(
                    TAG,
                    "Ghost Duel best score for $exercise could not be published from local history",
                    error
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Republishing local Ghost Duel best scores failed unexpectedly", e)
        }
    }
}
