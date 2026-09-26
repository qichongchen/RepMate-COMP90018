package com.repmate.data.sync

import android.util.Log
import com.repmate.data.cloud.FirestoreWorkoutDataSource
import com.repmate.data.local.RoomSessionRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.data.repo.BestWorkoutScore
import com.repmate.engine.ExerciseType
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncingSessionRepository @Inject constructor(
    private val roomRepository: RoomSessionRepository,
    private val firestoreWorkoutDataSource: FirestoreWorkoutDataSource,
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

    override suspend fun getMyBestScore(
        exercise: ExerciseType
    ): BestWorkoutScore? =
        roomRepository.getMyBestScore(exercise)
}