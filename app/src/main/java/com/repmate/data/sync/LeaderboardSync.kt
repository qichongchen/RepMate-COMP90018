package com.repmate.data.sync

import com.example.repmate.data.auth.AuthRepository
import com.repmate.data.repo.LeaderboardPoints
import com.repmate.engine.ExerciseType
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the signed-in user's row on the global leaderboard up to date.
 *
 * ## Why this exists
 * Nothing wrote the `leaderboard` collection. Its read path, queries, UI and empty states were all
 * built and working, so both leaderboard tabs and Home's top three were permanently empty -- not
 * a bug in the UI, just a missing producer. This is the producer.
 *
 * It is modelled on [LocalBestScoreSync] and runs from the same two places (after a workout is
 * saved, and after History restores sessions from the cloud), for the same reasons: a workout
 * finished offline still counts, and recomputing from local history is cheap and idempotent.
 *
 * ## What it writes
 * One number, from [LeaderboardPoints]: the best local session of each exercise, scored on average
 * rep score times rep count, summed. The display name comes from the user's profile and is
 * resolved by [LeaderboardWriter], because a leaderboard row with no name is useless.
 *
 * ## Best-effort, and guests are skipped
 * Nothing here throws except cancellation; a failure is returned for the caller to log. Guests are
 * skipped: their row would be keyed by a UID that stops existing the moment they sign in, and
 * their history is merged into the account they end up in anyway, which triggers a save and
 * therefore a fresh write.
 */
@Singleton
class LeaderboardSync
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val localSessions: LocalBestSessionSource,
        private val writer: LeaderboardWriter,
    ) {
        /**
         * Recomputes and writes the signed-in user's leaderboard row.
         *
         * @return the failure if the write could not be made, or null when it succeeded, when
         *   there is nothing to publish yet (no scored sessions), or when nobody eligible is
         *   signed in.
         */
        suspend fun publish(): Throwable? {
            val uid = authRepository.getCurrentUserId() ?: return null
            if (authRepository.isCurrentUserAnonymous()) return null

            return try {
                val bests =
                    ExerciseType.entries.mapNotNull { exercise ->
                        localSessions.bestSessionFor(exercise)?.let { LeaderboardPoints.bestOf(it) }
                    }
                // Nothing scored yet: writing a 0 would put the user on the board below everyone
                // who has actually worked out, which is worse than not being listed.
                if (bests.isEmpty()) return null

                // The writer targets whoever is signed in; if that changed while we were reading
                // Room, these points belong to someone else.
                if (authRepository.getCurrentUserId() != uid) return null

                writer.write(LeaderboardPoints.of(bests)).exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }
    }
