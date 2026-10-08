package com.repmate.data.cloud

import android.util.Log
import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.repmate.data.sync.LeaderboardWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes `leaderboard/{uid}` for the signed-in user: the producer the global leaderboard never had.
 *
 * The document holds exactly what a leaderboard row needs -- `displayName`, `points` and
 * `updatedAt` -- which is why the read path in [FirestoreLeaderboardRepository] needs no change:
 * it has always queried for these fields, there was simply nothing writing them.
 *
 * The name is read from `users/{uid}.displayName`, the same profile document Friends reads, so one
 * rename shows up everywhere. A user with no name yet is not written at all rather than listed as
 * "Unknown": an anonymous row on a leaderboard is noise.
 *
 * Logging lives here so [com.repmate.data.sync.LeaderboardSync] stays free of `android.util.Log`
 * and runs in a plain JVM test.
 */
@Singleton
class FirestoreLeaderboardWriter
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
        private val authRepository: AuthRepository,
    ) : LeaderboardWriter {
        private companion object {
            const val TAG = "RepMateLeaderboard"
        }

        override suspend fun write(points: Int): Result<Unit> {
            val uid =
                authRepository.getCurrentUserId()
                    ?: return failed(IllegalStateException("No authenticated user"))

            return try {
                val displayName =
                    firestore
                        .collection("users")
                        .document(uid)
                        .get()
                        .await()
                        .getString("displayName")
                        ?.takeIf { it.isNotBlank() }
                        ?: return failed(IllegalStateException("No display name to show on the leaderboard"))

                firestore
                    .collection("leaderboard")
                    .document(uid)
                    .set(
                        mapOf(
                            "displayName" to displayName,
                            "points" to points,
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        ),
                    ).await()

                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            }
        }

        private fun failed(error: Throwable): Result<Unit> {
            Log.w(TAG, "Could not write the leaderboard row", error)
            return Result.failure(error)
        }
    }
