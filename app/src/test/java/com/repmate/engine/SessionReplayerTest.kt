package com.repmate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `a jumping-jack session is replayed with the jumping-jack detector`() {
        val frames = pacedJackSet(reps = 5)
        val session = session(ExerciseType.JUMPING_JACK, frames)

        val replayed = SessionReplayer().replay(session, profile = null)

        // The same reps the live detector would count -- not what the squat detector makes of the
        // same frames, which is the bug this dispatch exists to prevent.
        val direct = JumpingJackRepDetector().processAll(frames)
        assertNotNull(replayed)
        assertEquals(direct.size, replayed!!.reps.size)
        assertEquals(direct.map { it.startMs }, replayed.reps.map { it.event.startMs })
        assertTrue(replayed.reps.all { it.curve.isNotEmpty() })
    }

    @Test
    fun `a jumping-jack replay is not what the squat detector would have produced`() {
        // Guards the dispatch itself: if both exercises went through SquatRepDetector this would
        // pass trivially and the jack curve would be cut into the wrong windows.
        val frames = pacedJackSet(reps = 5)

        val asJack = JumpingJackRepDetector().processAll(frames).size
        val asSquat = SquatRepDetector(null as CalibrationProfile?).processAll(frames).size

        assertTrue(
            "the two detectors must disagree on this trace for the dispatch test to mean anything",
            asJack != asSquat
        )
    }

    @Test
    fun `a push-up session is not replayed at all`() {
        // Counted from the camera: the IMU frames recorded alongside describe a phone held still.
        val session = session(ExerciseType.PUSHUP, syntheticSquatTrace(reps = 5))

        assertNull(SessionReplayer().replay(session, profile = null))
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

    private fun session(
        exercise: ExerciseType,
        frames: List<MotionFrame>
    ) = WorkoutSession(
        id = "replayed",
        exercise = exercise,
        startedAt = 0L,
        reps = emptyList(),
        frames = frames
    )

    /**
     * A paced jumping-jack set as flat 100 Hz segments: per rep, a jump-out impact and a landing
     * impact, each followed by the unweighting dip the Schmitt trigger needs to release.
     *
     * Same shape and magnitudes as `JumpingJackRepDetectorTest`'s own synthetic signal, which
     * documents where the numbers come from -- real recordings pin whether the thresholds are
     * right, synthetic traces pin the state machine.
     */
    private fun pacedJackSet(reps: Int): List<MotionFrame> {
        val frames = mutableListOf<MotionFrame>()
        var t = 0L

        fun hold(durationMs: Long, magnitude: Float) {
            val end = t + durationMs
            while (t < end) {
                frames += MotionFrame(t, 0f, magnitude, 0f, 0f, 0f, 0f)
                t += 10L
            }
        }

        fun impact(peak: Float, pulseMs: Long) {
            hold(pulseMs, peak)
            hold(250L, 9.0f)
        }

        hold(600L, 9.81f)
        repeat(reps) {
            impact(32f, 100L)
            impact(32f, 200L)
        }
        hold(600L, 9.81f)
        return frames
    }
}