package com.repmate.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.data.RepMateDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoBestScoreTest {

    private lateinit var database: RepMateDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RepMateDatabase::class.java
        ).build()

        dao = database.sessionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertWorkout(
        id: String,
        ownerId: String,
        exercise: String,
        startedAt: Long,
        scores: List<Float>
    ) {
        dao.replaceSession(
            session = WorkoutSessionEntity(
                id = id,
                ownerId = ownerId,
                exercise = exercise,
                startedAt = startedAt,
                endedAt = startedAt + 60_000L
            ),
            repScores = scores.mapIndexed { index, score ->
                RepScoreEntity(
                    sessionId = id,
                    repIndex = index,
                    score = score,
                    tempoSeconds = 2.0f,
                    rangePercent = 90,
                    pauseSeconds = 0f,
                    reasons = "[]"
                )
            }
        )
    }

    @Test
    fun returnsHighestSessionAverage() = runBlocking {
        insertWorkout(
            id = "squat-1",
            ownerId = "user-A",
            exercise = "SQUAT",
            startedAt = 1000L,
            scores = listOf(8f, 9f)
        )

        insertWorkout(
            id = "squat-2",
            ownerId = "user-A",
            exercise = "SQUAT",
            startedAt = 2000L,
            scores = listOf(7f, 7f, 7f)
        )

        val result = dao.getBestSessionScore(
            ownerId = "user-A",
            exercise = "SQUAT"
        )

        assertEquals(8.5f, result!!.averageScore, 0.001f)
        assertEquals(2, result.repCount)
    }

    @Test
    fun doesNotReturnAnotherUsersScore() = runBlocking {
        insertWorkout(
            id = "user-b-squat",
            ownerId = "user-B",
            exercise = "SQUAT",
            startedAt = 1000L,
            scores = listOf(10f, 10f)
        )

        val result = dao.getBestSessionScore(
            ownerId = "user-A",
            exercise = "SQUAT"
        )

        assertNull(result)
    }

    @Test
    fun filtersByExercise() = runBlocking {
        insertWorkout(
            id = "pushup-1",
            ownerId = "user-A",
            exercise = "PUSH_UP",
            startedAt = 1000L,
            scores = listOf(10f)
        )

        insertWorkout(
            id = "squat-1",
            ownerId = "user-A",
            exercise = "SQUAT",
            startedAt = 2000L,
            scores = listOf(8f, 9f)
        )

        val result = dao.getBestSessionScore(
            ownerId = "user-A",
            exercise = "SQUAT"
        )

        assertEquals(8.5f, result!!.averageScore, 0.001f)
        assertEquals(2, result.repCount)
    }

    @Test
    fun ignoresSessionsWithNoReps() = runBlocking {
        insertWorkout(
            id = "empty-squat",
            ownerId = "user-A",
            exercise = "SQUAT",
            startedAt = 1000L,
            scores = emptyList()
        )

        val result = dao.getBestSessionScore(
            ownerId = "user-A",
            exercise = "SQUAT"
        )

        assertNull(result)
    }

    @Test
    fun breaksAverageScoreTieUsingRepCount() = runBlocking {
        insertWorkout(
            id = "short-session",
            ownerId = "user-A",
            exercise = "SQUAT",
            startedAt = 2000L,
            scores = listOf(8f, 9f)
        )

        insertWorkout(
            id = "long-session",
            ownerId = "user-A",
            exercise = "SQUAT",
            startedAt = 1000L,
            scores = listOf(8f, 9f, 8f, 9f)
        )

        val result = dao.getBestSessionScore(
            ownerId = "user-A",
            exercise = "SQUAT"
        )

        assertEquals(8.5f, result!!.averageScore, 0.001f)
        assertEquals(4, result.repCount)
    }
}
