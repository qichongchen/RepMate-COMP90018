package com.repmate.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.data.auth.AuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.junit.BeforeClass
import org.junit.AfterClass

@RunWith(AndroidJUnit4::class)
class FirestoreGhostScoreDataSourceTest {

    companion object {
        private lateinit var firebaseApp: FirebaseApp
        private lateinit var firestore: FirebaseFirestore
        private lateinit var firebaseAuth: FirebaseAuth

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val defaultOptions =
                FirebaseApp.getInstance().options

            val options = FirebaseOptions.Builder()
                .setApplicationId(defaultOptions.applicationId)
                .setProjectId("repmate-556ae")
                .setApiKey(defaultOptions.apiKey)
                .build()

            firebaseApp = FirebaseApp.initializeApp(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext,
                options,
                "ghost-test-${UUID.randomUUID()}"
            )

            // Connect only to local Firebase Emulators.
            // Requires ADB reverse for ports 8080 and 9099.
            firestore =
                FirebaseFirestore.getInstance(firebaseApp)

            firestore.useEmulator("127.0.0.1", 8080)

            firebaseAuth =
                FirebaseAuth.getInstance(firebaseApp)

            firebaseAuth.useEmulator("127.0.0.1", 9099)
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() = runBlocking {
            firebaseAuth.signOut()
            firestore.terminate().await()
            firebaseApp.delete()
        }
    }

    private lateinit var auth: FakeAuthRepository
    private lateinit var dataSource: FirestoreGhostScoreDataSource
    private lateinit var userId: String

    @Before
    fun setUp() = runBlocking {
        // Each test gets a fresh anonymous account.
        firebaseAuth.signOut()

        val result =
            firebaseAuth.signInAnonymously().await()

        userId = requireNotNull(result.user?.uid)

        auth = FakeAuthRepository(userId)

        dataSource = FirestoreGhostScoreDataSource(
            firestore,
            auth
        )
    }

    @After
    fun tearDown() {
        firebaseAuth.signOut()
    }

    private fun makeSession(
        scores: List<Float>,
        startedAt: Long = 1000L,
        exercise: ExerciseType = ExerciseType.SQUAT
    ): WorkoutSession {
        return WorkoutSession(
            id = UUID.randomUUID().toString(),
            exercise = exercise,
            startedAt = startedAt,
            reps = scores.mapIndexed { index, score ->
                RepScore(
                    repIndex = index + 1,
                    score = score,
                    tempoSeconds = 2.0f,
                    rangePercent = 90,
                    pauseSeconds = 0.2f,
                    reasons = emptyList()
                )
            }
        )
    }

    private suspend fun readPublishedScore(): Pair<Double, Long>? {
        val snapshot = firestore
            .collection("users")
            .document(userId)
            .collection("ghostScores")
            .document("SQUAT")
            .get()
            .await()

        if (!snapshot.exists()) return null

        return Pair(
            snapshot.getDouble("averageScore")!!,
            snapshot.getLong("repCount")!!
        )
    }

    @Test
    fun publishesFirstValidSession() = runBlocking {
        val session = makeSession(listOf(8f, 9f))

        val result = dataSource.publishBestScore(session)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)

        val published = readPublishedScore()

        assertNotNull(published)
        assertEquals(8.5, published!!.first, 0.001)
        assertEquals(2L, published.second)
    }

    @Test
    fun higherAverageReplacesPreviousScore() = runBlocking {
        dataSource.publishBestScore(
            makeSession(listOf(7f, 8f))
        ).getOrThrow()

        dataSource.publishBestScore(
            makeSession(listOf(9f, 10f))
        ).getOrThrow()

        val published = readPublishedScore()!!

        assertEquals(9.5, published.first, 0.001)
        assertEquals(2L, published.second)
    }

    @Test
    fun lowerAverageDoesNotReplaceBestScore() = runBlocking {
        dataSource.publishBestScore(
            makeSession(listOf(9f, 10f))
        ).getOrThrow()

        dataSource.publishBestScore(
            makeSession(listOf(6f, 7f))
        ).getOrThrow()

        val published = readPublishedScore()!!

        assertEquals(9.5, published.first, 0.001)
        assertEquals(2L, published.second)
    }

    @Test
    fun equalAverageWithMoreRepsWins() = runBlocking {
        dataSource.publishBestScore(
            makeSession(listOf(8f, 8f))
        ).getOrThrow()

        dataSource.publishBestScore(
            makeSession(listOf(8f, 8f, 8f))
        ).getOrThrow()

        val published = readPublishedScore()!!

        assertEquals(8.0, published.first, 0.001)
        assertEquals(3L, published.second)
    }

    @Test
    fun zeroRepSessionIsNotPublished() = runBlocking {
        val result = dataSource.publishBestScore(
            makeSession(emptyList())
        )

        assertTrue(result.isSuccess)
        assertNull(readPublishedScore())
    }

    @Test
    fun readsPublishedScore() = runBlocking {
        dataSource.publishBestScore(
            makeSession(listOf(8f, 9f))
        ).getOrThrow()

        val result = dataSource.getFriendBestScore(
            userId,
            ExerciseType.SQUAT
        )

        assertTrue(result.isSuccess)
        assertEquals(8.5f, result.getOrThrow()!!.score, 0.001f)
        assertEquals(2, result.getOrThrow()!!.reps)
    }

    @Test
    fun missingScoreReturnsNull() = runBlocking {
        val result = dataSource.getFriendBestScore(
            userId,
            ExerciseType.SQUAT
        )

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    private class FakeAuthRepository(
        private val uid: String
    ) : AuthRepository {

        override suspend fun signInAnonymously(): Result<String> {
            return Result.success(uid)
        }

        override fun getCurrentUserId(): String = uid
    }
}
