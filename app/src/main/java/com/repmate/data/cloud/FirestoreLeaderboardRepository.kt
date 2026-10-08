package com.repmate.data.cloud

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.repmate.data.repo.ExerciseBest
import com.repmate.data.repo.LeaderboardEntry
import com.repmate.data.repo.LeaderboardPoints
import com.repmate.data.repo.LeaderboardRepository
import com.repmate.engine.ExerciseType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreLeaderboardRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : LeaderboardRepository {

    private companion object {
        const val TAG = "RepMateLeaderboard"
    }

    override fun topPlayers(limit: Int): Flow<List<LeaderboardEntry>> =
        callbackFlow {
            val listener = firestore
                .collection("leaderboard")
                .orderBy("points", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .addSnapshotListener { snapshot, error ->

                    if (error != null) {
                        // Logged here, where the Firestore error code is still visible
                        // (PERMISSION_DENIED etc.), then propagated: collectors turn it into a
                        // value with observeTop rather than letting it reach the main thread.
                        Log.w(TAG, "leaderboard query failed", error)
                        close(error)
                        return@addSnapshotListener
                    }

                    val entries = snapshot?.documents?.map { document ->
                        LeaderboardEntry(
                            userId = document.id,
                            displayName = document.getString("displayName") ?: "Unknown",
                            points = document.getLong("points")?.toInt() ?: 0,
                        )
                    }.orEmpty()

                    trySend(entries)
                }

            awaitClose {
                listener.remove()
            }
        }

    /**
     * The friends leaderboard, built from each friend's published `ghostScores` rather than from
     * the `leaderboard` collection.
     *
     * ## Why a different source from [topPlayers]
     * `leaderboard/{uid}` is written by `FirestoreLeaderboardWriter`, which needs its own Firestore
     * rule to be deployed before anything lands there. `ghostScores` is already being written (by
     * Ghost Duel, since #48/#57) and its documents are already readable one at a time by any
     * signed-in user, so this tab works with **no rules change at all**. If the leaderboard write
     * rule is not deployed, the Friends tab still shows real scores while Global is empty.
     *
     * ## What it costs
     * Four reads per user: one `get` per exercise plus one for the profile name. `ghostScores` has
     * `allow list: if false`, so there is no way to fetch them in one query -- that is deliberate
     * in the rules, not an oversight here. For a set of friends this is cheap; it would not scale
     * to hundreds, and [topPlayers] is the query to use at that size.
     *
     * NOTE: no `whereIn`. The old implementation queried `leaderboard` with
     * `whereIn(documentId(), friendUserIds)`, which Firestore caps at **30 values** -- a 31st
     * friend failed the whole query. Reading per user removes that cap. Anything reintroducing a
     * batched query here has to chunk the ids to stay under it.
     *
     * One-shot rather than a snapshot listener: scores change when a workout ends, not while the
     * screen is open, and [com.repmate.ui.leaderboard.LeaderboardViewModel] re-subscribes whenever
     * the friends list changes.
     */
    override fun friendsLeaderboard(
        friendUserIds: List<String>
    ): Flow<List<LeaderboardEntry>> =
        flow {
            if (friendUserIds.isEmpty()) {
                emit(emptyList())
                return@flow
            }

            val entries = friendUserIds.distinct().mapNotNull { userId -> entryFor(userId) }
            emit(entries.sortedByDescending { it.points })
        }

    /**
     * One leaderboard row for [userId], or null when they have no name or nothing scored yet --
     * a nameless or 0-point row is noise on a leaderboard, not information.
     *
     * A failure reading one user is logged and skipped rather than failing the whole tab: one
     * friend with a missing profile should not blank the leaderboard.
     */
    private suspend fun entryFor(userId: String): LeaderboardEntry? =
        try {
            val displayName = firestore
                .collection("users")
                .document(userId)
                .get()
                .await()
                .getString("displayName")
                ?.takeIf { it.isNotBlank() }

            val bests = ExerciseType.entries.mapNotNull { exercise ->
                val document = firestore
                    .collection("users")
                    .document(userId)
                    .collection("ghostScores")
                    .document(exercise.name)
                    .get()
                    .await()

                val averageScore = document.getDouble("averageScore")
                val repCount = document.getLong("repCount")?.toInt()
                if (averageScore == null || repCount == null) {
                    null
                } else {
                    ExerciseBest(averageScore = averageScore, repCount = repCount)
                }
            }

            if (displayName == null || bests.isEmpty()) {
                null
            } else {
                LeaderboardEntry(
                    userId = userId,
                    displayName = displayName,
                    points = LeaderboardPoints.of(bests),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the leaderboard row for $userId", e)
            null
        }
}