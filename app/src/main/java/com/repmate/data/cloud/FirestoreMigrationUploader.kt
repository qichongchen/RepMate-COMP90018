package com.repmate.data.cloud

import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.sync.MigrationUploader
import com.repmate.engine.WorkoutSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MigrationUploader] that reuses [FirestoreWorkoutDataSource.upload], which `set`s
 * `users/{currentUid}/workoutSessions/{id}` -- an upsert, so re-uploading is harmless.
 *
 * That call always writes under the *signed-in* user, so this checks first that the signed-in
 * user really is [targetUid] and refuses otherwise: a merge must never write into any other
 * account's documents.
 */
@Singleton
class FirestoreMigrationUploader
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val workoutUploader: WorkoutUploader,
    ) : MigrationUploader {
        override suspend fun upload(
            session: WorkoutSession,
            targetUid: String,
        ): Result<Unit> {
            if (authRepository.getCurrentUserId() != targetUid) {
                return Result.failure(IllegalStateException("Signed-in user is not the merge target"))
            }
            return workoutUploader.upload(session)
        }
    }
