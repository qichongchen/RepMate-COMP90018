package com.repmate.data.cloud

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.sync.GhostScorePublisher
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GhostScorePublisher] that delegates to [FirestoreGhostScoreDataSource.publishBestScore].
 *
 * A thin adapter: [FirestoreGhostScoreDataSource] is a concrete Firestore class, so the guest
 * migrator depends on the interface instead and stays unit-testable. `publishBestScore` only
 * replaces a stored score with a better one, which is what makes publishing the same session
 * twice harmless.
 *
 * It writes under the *signed-in* user, so this checks first that the signed-in user really is
 * `targetUid` -- at call time, not when the merge started -- and refuses otherwise: a merge must
 * never put a score into another account's `ghostScores`.
 *
 * Logging lives here, not in the migrator, so the migrator stays free of `android.util.Log` and
 * runs in a plain JVM test. Every failure is logged and returned as a [Result]; only
 * cancellation propagates.
 */
@Singleton
class FirestoreGhostScorePublisher
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val ghostScores: FirestoreGhostScoreDataSource,
    ) : GhostScorePublisher {
        override suspend fun publish(
            session: WorkoutSession,
            targetUid: String,
        ): Result<Unit> {
            if (authRepository.getCurrentUserId() != targetUid) {
                return failed(session, IllegalStateException("Signed-in user is not the merge target"))
            }
            return try {
                ghostScores.publishBestScore(session).onFailure { error -> logFailure(session, error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(session, e)
            }
        }

        private fun failed(
            session: WorkoutSession,
            error: Throwable,
        ): Result<Unit> {
            logFailure(session, error)
            return Result.failure(error)
        }

        private fun logFailure(
            session: WorkoutSession,
            error: Throwable,
        ) {
            Log.w(TAG, "Guest merge: Ghost Duel score for ${session.exercise} (session ${session.id}) was not published", error)
        }

        private companion object {
            const val TAG = "GhostScorePublisher"
        }
    }
