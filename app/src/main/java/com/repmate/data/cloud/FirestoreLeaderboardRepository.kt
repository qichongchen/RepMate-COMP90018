package com.repmate.data.cloud

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.repmate.data.repo.LeaderboardEntry
import com.repmate.data.repo.LeaderboardRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreLeaderboardRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : LeaderboardRepository {

    override fun topPlayers(limit: Int): Flow<List<LeaderboardEntry>> =
        callbackFlow {
            val listener = firestore
                .collection("leaderboard")
                .orderBy("points", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .addSnapshotListener { snapshot, error ->

                    if (error != null) {
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
}