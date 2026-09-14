package com.repmate.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.data.RepMateDatabase
import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomSessionRepositoryTest {

    private lateinit var database: RepMateDatabase
    private lateinit var repository: RoomSessionRepository

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RepMateDatabase::class.java
        ).build()

        repository = RoomSessionRepository(
            database.sessionDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveAndReadSession() = runTest {
        val session = WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = listOf(
                RepScore(
                    repIndex = 1,
                    score = 8.5f,
                    tempoSeconds = 2.0f,
                    rangePercent = 90,
                    pauseSeconds = 0.3f,
                    reasons = listOf("good depth")
                )
            )
        )

        repository.save(session)

        val result = repository.recent(10).first()

        assertEquals(1, result.size)
        assertEquals("session-1", result[0].id)
        assertEquals(ExerciseType.SQUAT, result[0].exercise)
        assertEquals(1, result[0].reps.size)
    }

    @Test
    fun recentReturnsSessionsNewestFirst() = runTest {
        val olderSession = WorkoutSession(
            id = "older",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = emptyList()
        )

        val newerSession = WorkoutSession(
            id = "newer",
            exercise = ExerciseType.PUSHUP,
            startedAt = 2000L,
            reps = emptyList()
        )

        repository.save(olderSession)
        repository.save(newerSession)

        val result = repository.recent(10).first()

        assertEquals(2, result.size)
        assertEquals("newer", result[0].id)
        assertEquals("older", result[1].id)
    }

    @Test
    fun saveWithSameIdReplacesExistingSession() = runTest {
        val originalSession = WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = listOf(
                RepScore(
                    repIndex = 1,
                    score = 7.0f,
                    tempoSeconds = 2.0f,
                    rangePercent = 80,
                    pauseSeconds = 0.2f,
                    reasons = listOf("original")
                )
            )
        )

        val updatedSession = WorkoutSession(
            id = "session-1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1000L,
            reps = listOf(
                RepScore(
                    repIndex = 1,
                    score = 9.0f,
                    tempoSeconds = 1.8f,
                    rangePercent = 95,
                    pauseSeconds = 0.1f,
                    reasons = listOf("updated")
                )
            )
        )

        repository.save(originalSession)
        repository.save(updatedSession)

        val result = repository.recent(10).first()

        assertEquals(1, result.size)
        assertEquals(9.0f, result[0].reps[0].score)
        assertEquals(listOf("updated"), result[0].reps[0].reasons)
    }
}