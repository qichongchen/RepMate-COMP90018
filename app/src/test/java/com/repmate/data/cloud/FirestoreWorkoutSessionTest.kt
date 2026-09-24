package com.repmate.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirestoreWorkoutSessionTest {

    @Test
    fun `toRoomSession maps session fields correctly`() {
        val cloudSession = FirestoreWorkoutSession(
            id = "session-123",
            userId = "cloud-user",
            exercise = "SQUAT",
            startedAt = 1000L,
            endedAt = 5000L,
            reps = emptyList()
        )

        val entity = cloudSession.toRoomSession(
            ownerId = "current-user"
        )

        assertEquals("session-123", entity.id)
        assertEquals("current-user", entity.ownerId)
        assertEquals("SQUAT", entity.exercise)
        assertEquals(1000L, entity.startedAt)
        assertEquals(5000L, entity.endedAt)
    }

    @Test
    fun `toRoomSession preserves null endedAt`() {
        val cloudSession = FirestoreWorkoutSession(
            id = "old-session",
            userId = "cloud-user",
            exercise = "SQUAT",
            startedAt = 1000L,
            endedAt = null,
            reps = emptyList()
        )

        val entity = cloudSession.toRoomSession(
            ownerId = "current-user"
        )

        assertNull(entity.endedAt)
    }

    @Test
    fun `toRoomRepScores converts reasons to pipe separated string`() {
        val cloudSession = FirestoreWorkoutSession(
            id = "session-123",
            userId = "user-1",
            exercise = "SQUAT",
            startedAt = 1000L,
            endedAt = 5000L,
            reps = listOf(
                FirestoreRepScore(
                    repIndex = 0,
                    score = 80,
                    tempoSeconds = 2.5,
                    rangePercent = 90.0,
                    pauseSeconds = 0.5,
                    reasons = listOf(
                        "Too shallow",
                        "Fast tempo"
                    )
                )
            )
        )

        val reps = cloudSession.toRoomRepScores()

        assertEquals(1, reps.size)

        val rep = reps.first()

        assertEquals("session-123", rep.sessionId)
        assertEquals(0, rep.repIndex)
        assertEquals(80f, rep.score)
        assertEquals(2.5f, rep.tempoSeconds)
        assertEquals(90, rep.rangePercent)
        assertEquals(0.5f, rep.pauseSeconds)

        assertEquals(
            "Too shallow|Fast tempo",
            rep.reasons
        )
    }
}