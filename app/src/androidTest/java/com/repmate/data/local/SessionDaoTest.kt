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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var database: RepMateDatabase
    private lateinit var sessionDao: SessionDao

    private val ownerId = "test-user"

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
            ownerId = ownerId,
            exercise = "SQUAT",
            startedAt = 1000L
        )

        sessionDao.insertSession(session)

        val sessions =
            sessionDao.observeRecentSessions(
                ownerId = ownerId,
                limit = 10
            ).first()

        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("SQUAT", sessions[0].exercise)
        assertEquals(1000L, sessions[0].startedAt)
    }

    @Test
    fun replaceSession_replacesOldRepScores() = runTest {
        val session = WorkoutSessionEntity(
            id = "session-1",
            ownerId = ownerId,
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
                ownerId = ownerId,
                exercise = "SQUAT",
                startedAt = 1000L
            )
        )

        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "new",
                ownerId = ownerId,
                exercise = "SQUAT",
                startedAt = 3000L
            )
        )

        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "middle",
                ownerId = ownerId,
                exercise = "SQUAT",
                startedAt = 2000L
            )
        )

        val sessions =
            sessionDao.observeRecentSessions(
                ownerId = ownerId,
                limit = 10
            ).first()

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
                    ownerId = ownerId,
                    exercise = "SQUAT",
                    startedAt = index.toLong()
                )
            )
        }

        val sessions =
            sessionDao.observeRecentSessions(
                ownerId = ownerId,
                limit = 2
            ).first()

        assertEquals(2, sessions.size)
        assertEquals("session-4", sessions[0].id)
        assertEquals("session-3", sessions[1].id)
    }

    @Test
    fun observeRecentSessions_onlyReturnsCurrentOwner() = runTest {
        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "user-a-session",
                ownerId = "user-a",
                exercise = "SQUAT",
                startedAt = 1000L
            )
        )

        sessionDao.insertSession(
            WorkoutSessionEntity(
                id = "user-b-session",
                ownerId = "user-b",
                exercise = "SQUAT",
                startedAt = 2000L
            )
        )

        val sessions =
            sessionDao.observeRecentSessions(
                ownerId = "user-a",
                limit = 10
            ).first()

        assertEquals(1, sessions.size)
        assertEquals("user-a-session", sessions[0].id)
        assertEquals("user-a", sessions[0].ownerId)
    }


    @Test
    fun insertSessionIfMissing_insertsSessionAndReps() = runTest {
        val session = WorkoutSessionEntity(
            id = "restored-session",
            ownerId = ownerId,
            exercise = "SQUAT",
            startedAt = 1000L,
            endedAt = 5000L
        )

        val reps = listOf(
            RepScoreEntity(
                sessionId = "restored-session",
                repIndex = 0,
                score = 80f,
                tempoSeconds = 2.5f,
                rangePercent = 90,
                pauseSeconds = 0.5f,
                reasons = "Too shallow|Fast tempo"
            )
        )

        val inserted = sessionDao.insertSessionIfMissing(
            session = session,
            repScores = reps
        )

        assertTrue(inserted)

        val restored = sessionDao.getSessionById(
            id = "restored-session",
            ownerId = ownerId
        )

        assertNotNull(restored)
        assertEquals(5000L, restored?.endedAt)

        val restoredReps =
            sessionDao.getRepScores("restored-session")

        assertEquals(1, restoredReps.size)
        assertEquals(80f, restoredReps[0].score)
        assertEquals(
            "Too shallow|Fast tempo",
            restoredReps[0].reasons
        )
    }

    @Test
    fun insertSessionIfMissing_preservesExistingSession() = runTest {
        val existing = WorkoutSessionEntity(
            id = "session-1",
            ownerId = ownerId,
            exercise = "SQUAT",
            startedAt = 1000L,
            endedAt = 2000L
        )

        val existingRep = RepScoreEntity(
            sessionId = "session-1",
            repIndex = 0,
            score = 80f,
            tempoSeconds = 2f,
            rangePercent = 90,
            pauseSeconds = 0.5f,
            reasons = "Original"
        )

        sessionDao.replaceSession(
            existing,
            listOf(existingRep)
        )

        val cloudVersion = existing.copy(
            endedAt = 9999L
        )

        val cloudRep = existingRep.copy(
            score = 95f,
            reasons = "Updated"
        )

        val inserted = sessionDao.insertSessionIfMissing(
            session = cloudVersion,
            repScores = listOf(cloudRep)
        )

        assertFalse(inserted)

        val stored = sessionDao.getSessionById(
            id = "session-1",
            ownerId = ownerId
        )

        val storedReps = sessionDao.getRepScores("session-1")

        assertEquals(2000L, stored?.endedAt)
        assertEquals(1, storedReps.size)
        assertEquals(80f, storedReps[0].score)
        assertEquals("Original", storedReps[0].reasons)
    }

    @Test
    fun insertSessionIfMissing_preservesLocalOnlySessions() = runTest {
        val localSession = WorkoutSessionEntity(
            id = "local-only",
            ownerId = ownerId,
            exercise = "SQUAT",
            startedAt = 1000L
        )

        sessionDao.insertSession(localSession)

        val cloudSession = WorkoutSessionEntity(
            id = "cloud-only",
            ownerId = ownerId,
            exercise = "SQUAT",
            startedAt = 2000L
        )

        val inserted = sessionDao.insertSessionIfMissing(
            session = cloudSession,
            repScores = emptyList()
        )

        assertTrue(inserted)

        val sessions = sessionDao.observeRecentSessions(
            ownerId = ownerId,
            limit = 10
        ).first()

        assertEquals(2, sessions.size)
        assertEquals(
            listOf("cloud-only", "local-only"),
            sessions.map { it.id }
        )
    }
}