package com.repmate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionReplayerTest {

    private lateinit var replayer: SessionReplayer

    @Before
    fun setUp() {
        replayer = SessionReplayer()
    }

    @Test
    fun `replay returns null when the session has no frames`() {
        val session = WorkoutSession(
            id = "s1",
            exercise = ExerciseType.SQUAT,
            startedAt = 0L,
            reps = emptyList(),
            frames = null
        )

        assertNull(replayer.replay(session, profile = null))
    }

    @Test
    fun `replay reproduces the same rep count as detecting directly`() {
        val frames = syntheticSquatTrace(reps = 5)
        val expectedEvents = SquatRepDetector(profile = null).processAll(frames)

        val session = WorkoutSession(
            id = "s2",
            exercise = ExerciseType.SQUAT,
            startedAt = 0L,
            reps = emptyList(),
            frames = frames
        )
        val result = replayer.replay(session, profile = null)

        assertEquals(expectedEvents.size, result?.reps?.size)
        assertEquals(expectedEvents, result?.reps?.map { it.event })
    }

    @Test
    fun `each replayed rep's curve stays within that rep's own time window`() {
        val frames = syntheticSquatTrace(reps = 5)
        val session = WorkoutSession(
            id = "s3",
            exercise = ExerciseType.SQUAT,
            startedAt = 0L,
            reps = emptyList(),
            frames = frames
        )

        val result = replayer.replay(session, profile = null)!!

        result.reps.forEach { rep ->
            assertTrue(rep.curve.isNotEmpty())
            assertTrue(rep.curve.all { it.tMillis in rep.event.startMs..rep.event.endMs })
        }
    }

    @Test
    fun `replayed scores match calling FormScorer directly with the same reps`() {
        val frames = syntheticSquatTrace(reps = 5)
        val events = SquatRepDetector(profile = null).processAll(frames)
        val expectedScores = events.mapIndexed { i, event ->
            FormScorer().score(event, profile = null, previousReps = events.subList(0, i))
        }

        val session = WorkoutSession(
            id = "s4",
            exercise = ExerciseType.SQUAT,
            startedAt = 0L,
            reps = emptyList(),
            frames = frames
        )
        val result = replayer.replay(session, profile = null)!!

        assertEquals(expectedScores, result.reps.map { it.score })
    }
}