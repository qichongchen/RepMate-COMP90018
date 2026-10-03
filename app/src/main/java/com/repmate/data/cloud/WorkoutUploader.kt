package com.repmate.data.cloud

import com.repmate.engine.WorkoutSession

/**
 * Writes one workout session to the signed-in user's cloud copy.
 *
 * The one method of [FirestoreWorkoutDataSource] that the guest merge needs, pulled out as an
 * interface so [FirestoreMigrationUploader] can be tested without a FirebaseFirestore instance.
 * Implementations upsert by `session.id`, so re-uploading the same session is harmless.
 */
interface WorkoutUploader {
    suspend fun upload(session: WorkoutSession): Result<Unit>
}
