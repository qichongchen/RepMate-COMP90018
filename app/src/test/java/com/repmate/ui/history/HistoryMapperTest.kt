package com.repmate.ui.history

import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE = ZoneId.of("UTC")

private val NOW = ZonedDateTime.of(2026, 9, 23, 12, 0, 0, 0, ZONE)

private fun sessionAt(id: String, dateTime: ZonedDateTime) =
    WorkoutSession(
        id = id,
        exercise = ExerciseType.SQUAT,
        startedAt = dateTime.toInstant().toEpochMilli(),
        reps = listOf(RepScore(repIndex = 0, score = 8f, tempoSeconds = 1f, rangePercent = 90, pauseSeconds = 0f, reasons = emptyList())),
    )

class HistoryMapperTest {

    @Test
    fun `buckets a session from this month under its calendar month label`() {
        val sections = groupSessionsIntoSections(listOf(sessionAt("a", NOW)), zoneId = ZONE, now = NOW)

        assertEquals(listOf("September 2026"), sections.map { it.label })
        assertEquals("a", sections.single().sessions.single().id)
    }

    @Test
    fun `buckets a session from last month under its own calendar month label`() {
        val session = sessionAt("a", ZonedDateTime.of(2026, 8, 15, 12, 0, 0, 0, ZONE))

        val sections = groupSessionsIntoSections(listOf(session), zoneId = ZONE, now = NOW)

        assertEquals(listOf("August 2026"), sections.map { it.label })
    }

    @Test
    fun `buckets a session from several months ago under its own calendar month label`() {
        val session = sessionAt("a", ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZONE))

        val sections = groupSessionsIntoSections(listOf(session), zoneId = ZONE, now = NOW)

        assertEquals(listOf("March 2026"), sections.map { it.label })
    }

    @Test
    fun `a December session lands in its own month against a now in January`() {
        val now = ZonedDateTime.of(2027, 1, 5, 12, 0, 0, 0, ZONE)
        val decemberSession = sessionAt("a", ZonedDateTime.of(2026, 12, 29, 12, 0, 0, 0, ZONE))

        val sections = groupSessionsIntoSections(listOf(decemberSession), zoneId = ZONE, now = now)

        assertEquals(listOf("December 2026"), sections.map { it.label })
    }

    @Test
    fun `omits a bucket entirely when it has no sessions`() {
        val sections = groupSessionsIntoSections(listOf(sessionAt("a", NOW)), zoneId = ZONE, now = NOW)

        assertTrue(sections.none { it.label == "August 2026" })
    }

    @Test
    fun `an empty session list produces no sections`() {
        assertTrue(groupSessionsIntoSections(emptyList(), zoneId = ZONE, now = NOW).isEmpty())
    }

    @Test
    fun `preserves the caller's newest-first ordering within a bucket`() {
        val newer = sessionAt("newer", NOW)
        val older = sessionAt("older", NOW.minusHours(2))

        val sections = groupSessionsIntoSections(listOf(newer, older), zoneId = ZONE, now = NOW)

        assertEquals(listOf("newer", "older"), sections.single().sessions.map { it.id })
    }

    @Test
    fun `orders sections newest month first`() {
        val september = sessionAt("september", NOW)
        val august = sessionAt("august", ZonedDateTime.of(2026, 8, 15, 12, 0, 0, 0, ZONE))
        val july = sessionAt("july", ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZONE))

        val sections = groupSessionsIntoSections(listOf(september, august, july), zoneId = ZONE, now = NOW)

        assertEquals(listOf("September 2026", "August 2026", "July 2026"), sections.map { it.label })
    }

    @Test
    fun `averageScore is 0 for a session with no reps`() {
        val session = WorkoutSession(id = "a", exercise = ExerciseType.SQUAT, startedAt = 0L, reps = emptyList())

        assertEquals(0f, session.averageScore(), 0.001f)
    }

    @Test
    fun `averageScore averages every rep's score`() {
        val session = WorkoutSession(
            id = "a",
            exercise = ExerciseType.SQUAT,
            startedAt = 0L,
            reps = listOf(
                RepScore(repIndex = 0, score = 6f, tempoSeconds = 1f, rangePercent = 90, pauseSeconds = 0f, reasons = emptyList()),
                RepScore(repIndex = 1, score = 8f, tempoSeconds = 1f, rangePercent = 90, pauseSeconds = 0f, reasons = emptyList()),
            ),
        )

        assertEquals(7f, session.averageScore(), 0.001f)
    }

    @Test
    fun `durationLabel formats a real endedAt as M SS`() {
        val session = WorkoutSession(id = "a", exercise = ExerciseType.SQUAT, startedAt = 0L, reps = emptyList(), endedAt = 192_000L)

        assertEquals("3:12", session.durationLabel())
    }

    @Test
    fun `durationLabel is null when endedAt is missing`() {
        val session = WorkoutSession(id = "a", exercise = ExerciseType.SQUAT, startedAt = 0L, reps = emptyList(), endedAt = null)

        assertEquals(null, session.durationLabel())
    }

    @Test
    fun `durationLabel is null when endedAt is at or before startedAt`() {
        val equal = WorkoutSession(id = "a", exercise = ExerciseType.SQUAT, startedAt = 1_000L, reps = emptyList(), endedAt = 1_000L)
        val before = WorkoutSession(id = "b", exercise = ExerciseType.SQUAT, startedAt = 1_000L, reps = emptyList(), endedAt = 500L)

        assertEquals(null, equal.durationLabel())
        assertEquals(null, before.durationLabel())
    }
}
