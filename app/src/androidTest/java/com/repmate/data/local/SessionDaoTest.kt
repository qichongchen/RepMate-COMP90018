package com.repmate.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.data.RepMateDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var database: RepMateDatabase
    private lateinit var sessionDao: SessionDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RepMateDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        sessionDao = database.sessionDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertSession_canBeObserved() = runTest {
        val session = WorkoutSessionEntity(
            id = "session-1",
            exercise = "SQUAT",
            startedAt = 1000L
        )

        sessionDao.insertSession(session)

        val sessions = sessionDao.observeRecentSessions(10).first()

        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("SQUAT", sessions[0].exercise)
        assertEquals(1000L, sessions[0].startedAt)
    }

    @Test
    fun replaceSession_replacesOldRepScores() = runTest {
        val session = WorkoutSessionEntity(
            id = "session-1",
            exercise = "SQUAT",
            startedAt = 1000L
        )

        val oldReps = listOf(
            RepScoreEntity(
                sessionId = "session-1",
                repIndex = 0,
                score = 70f,
                tempoSeconds = 2f,
                rangePercent = 80,
                pauseSeconds = 0.5f,
                reasons = "Old"
            ),
            RepScoreEntity(
                sessionId = "session-1",
                repIndex = 1,
                score = 75f,
                tempoSeconds = 2.2f,
                rangePercent = 85,
                pauseSeconds = 0.4f,
                reasons = "Old"
            )
        )

        sessionDao.replaceSession(session, oldReps)

        val newReps = listOf(
            RepScoreEntity(
                sessionId = "session-1",
                repIndex = 0,
                score = 95f,
                tempoSeconds = 2.5f,
                rangePercent = 100,
                pauseSeconds = 0.2f,
                reasons = "Updated"
            )
        )

        sessionDao.replaceSession(session, newReps)

        val storedReps = sessionDao.getRepScores("session-1")

        assertEquals(1, storedReps.size)
        assertEquals(95f, storedReps[0].score)
        assertEquals("Updated", storedReps[0].reasons)
    }

    @Test
    fun observeRecentSessions_ordersNewestFirst() = runTest {
        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "old",
                exercise = "SQUAT",
                startedAt = 1000L
            )
        )

        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "new",
                exercise = "SQUAT",
                startedAt = 3000L
            )
        )

        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "middle",
                exercise = "SQUAT",
                startedAt = 2000L
            )
        )

        val sessions = sessionDao.observeRecentSessions(10).first()

        assertEquals(
            listOf("new", "middle", "old"),
            sessions.map { it.id }
        )
    }

    @Test
    fun observeRecentSessions_respectsLimit() = runTest {
        repeat(5) { index ->
            sessionDao.insertSession(
                WorkoutSessionEntity(
                    id = "session-$index",
                    exercise = "SQUAT",
                    startedAt = index.toLong()
                )
            )
        }

        val sessions = sessionDao.observeRecentSessions(2).first()

        assertEquals(2, sessions.size)
        assertEquals("session-4", sessions[0].id)
        assertEquals("session-3", sessions[1].id)
    }
}