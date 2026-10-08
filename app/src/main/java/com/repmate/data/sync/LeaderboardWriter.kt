package com.repmate.data.sync

/**
 * Writes the signed-in user's own row on the global leaderboard.
 *
 * An interface so [LeaderboardSync] can be unit tested without FirebaseFirestore, the same split
 * [BestScorePublisher] uses. The implementation resolves the display name itself, so the sync
 * class only has to work out the points.
 */
interface LeaderboardWriter {
    /**
     * Upserts `leaderboard/{uid}` for the signed-in user with [points] and their display name.
     *
     * Must write only under the signed-in user, and must not throw: a leaderboard row is the least
     * important thing in a workout save.
     */
    suspend fun write(points: Int): Result<Unit>
}
