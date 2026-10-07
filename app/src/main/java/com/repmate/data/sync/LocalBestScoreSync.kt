package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.engine.ExerciseType
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Republishes the signed-in user's best **local** session of each exercise as their Ghost Duel
 * score.
 *
 * ## Why this exists
 * `SyncingSessionRepository.save` used to publish a score only right after a successful upload,
 * once. A workout finished offline never got that chance, so friends would never see the score
 * unless a later session beat it. Publishing from what is *stored locally*, rather than from the
 * session just saved, means any call (after every save, after a History restore) catches up every
 * exercise, including sessions that were never published, and the session just saved is covered
 * because it is stored by then.
 *
 * ## Why it is cheap and safe to repeat
 * The store only overwrites a score with a better one, so publishing the same best session again
 * changes nothing. There is deliberately no "published" flag in Room: the cloud copy is the
 * record of what is published, and asking again costs one transaction per exercise that has a
 * session.
 *
 * ## Best-effort
 * Nothing here throws, apart from cancellation. A failed publish, or a failure reading Room, is
 * returned to the caller rather than raised, and every exercise is attempted regardless of the
 * others. The failures are *returned* instead of logged so this class stays free of
 * `android.util.Log` and runs in a plain JVM test; the caller logs them.
 *
 * Guests (anonymous users) are skipped: their score would land under a UID that stops existing
 * once they sign in, and the guest merge republishes for the account they end up in.
 */
@Singleton
class LocalBestScoreSync
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val localSessions: LocalBestSessionSource,
        private val publisher: BestScorePublisher,
    ) {
        /**
         * Publishes the best local session of every exercise that has one with reps.
         *
         * @return the failure for each exercise that could not be published (empty when all went
         *   through, or when there was nothing to do, nobody signed in, or the user is a guest).
         */
        suspend fun publishAll(): Map<ExerciseType, Throwable> {
            val uid = authRepository.getCurrentUserId() ?: return emptyMap()
            if (authRepository.isCurrentUserAnonymous()) return emptyMap()

            val failures = linkedMapOf<ExerciseType, Throwable>()
            for (exercise in ExerciseType.entries) {
                try {
                    val best = localSessions.bestSessionFor(exercise)?.takeIf { it.reps.isNotEmpty() } ?: continue
                    // The publisher writes under whoever is signed in *now*; if that is no longer
                    // the user these sessions belong to, stop rather than publish them elsewhere.
                    if (authRepository.getCurrentUserId() != uid) break
                    publisher.publishBestScore(best).onFailure { error -> failures[exercise] = error }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One exercise failing (a Room read, a throwing publisher) must not skip the rest.
                    failures[exercise] = e
                }
            }
            return failures
        }
    }
