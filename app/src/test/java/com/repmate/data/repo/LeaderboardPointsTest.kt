package com.repmate.data.repo

import com.repmate.engine.ExerciseType
import com.repmate.engine.RepScore
import com.repmate.engine.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LeaderboardPoints] is the whole definition of a leaderboard score, and both tabs compute it:
 * the global producer from local sessions, the friends tab from published `ghostScores`. These
 * tests pin the rule so the two can never drift apart, and so a change to it is a deliberate one.
 */
class LeaderboardPointsTest {
    @Test
    fun oneExerciseScoresAverageTimesReps() {
        // A 10-rep set averaging 8.0 is 80 points.
        assertEquals(80, LeaderboardPoints.of(listOf(ExerciseBest(averageScore = 8.0, repCount = 10))))
    }

    @Test
    fun exercisesAddUp() {
        val bests =
            listOf(
                ExerciseBest(averageScore = 8.0, repCount = 10),
                ExerciseBest(averageScore = 7.5, repCount = 12),
            )

        assertEquals(80 + 90, LeaderboardPoints.of(bests))
    }

    @Test
    fun moreRepsAtTheSameQualityScoresHigher() {
        val fewer = LeaderboardPoints.of(listOf(ExerciseBest(8.0, 10)))
        val more = LeaderboardPoints.of(listOf(ExerciseBest(8.0, 20)))

        assertEquals(true, more > fewer)
    }

    @Test
    fun betterFormAtTheSameRepCountScoresHigher() {
        val sloppy = LeaderboardPoints.of(listOf(ExerciseBest(5.0, 10)))
        val clean = LeaderboardPoints.of(listOf(ExerciseBest(9.0, 10)))

        assertEquals(true, clean > sloppy)
    }

    @Test
    fun aCarefulShortSetDoesNotOutrankACarefulLongOne() {
        // The reason points are a product and not a score: three perfect reps should not beat
        // thirty good ones.
        val threePerfect = LeaderboardPoints.of(listOf(ExerciseBest(10.0, 3)))
        val thirtyGood = LeaderboardPoints.of(listOf(ExerciseBest(7.0, 30)))

        assertEquals(true, thirtyGood > threePerfect)
    }

    @Test
    fun nothingScoredIsZeroRatherThanAnError() {
        assertEquals(0, LeaderboardPoints.of(emptyList()))
    }

    @Test
    fun aRepCountOrScoreOfZeroContributesNothing() {
        assertEquals(0, LeaderboardPoints.pointsFor(ExerciseBest(averageScore = 8.0, repCount = 0)))
        assertEquals(0, LeaderboardPoints.pointsFor(ExerciseBest(averageScore = 0.0, repCount = 10)))
    }

    @Test
    fun anImpossibleNegativeScoreCannotSubtractPoints() {
        // Defensive: Firestore data is read as a plain number, so a bad document must not be able
        // to drag a total down.
        assertEquals(0, LeaderboardPoints.pointsFor(ExerciseBest(averageScore = -5.0, repCount = 10)))
        assertEquals(0, LeaderboardPoints.pointsFor(ExerciseBest(averageScore = 8.0, repCount = -3)))
    }

    @Test
    fun pointsAreRoundedNotTruncated() {
        // 7.55 x 10 = 75.5 -> 76.
        assertEquals(76, LeaderboardPoints.pointsFor(ExerciseBest(averageScore = 7.55, repCount = 10)))
    }

    @Test
    fun aSessionBecomesItsAverageAndRepCount() {
        val best = LeaderboardPoints.bestOf(session(scores = listOf(6f, 8f, 10f)))

        assertEquals(ExerciseBest(averageScore = 8.0, repCount = 3), best)
    }

    @Test
    fun aSessionWithNoRepsIsNotScorable() {
        // A 0-rep session is still the user's data, but it is not an achievement.
        assertNull(LeaderboardPoints.bestOf(session(scores = emptyList())))
    }

    @Test
    fun theSessionPathAndTheGhostScorePathAgree() {
        // The friends tab reads averageScore/repCount straight out of ghostScores; the global
        // producer derives them from the session. Same session, same points, or the two tabs
        // would rank the same person differently.
        val workout = session(scores = listOf(7f, 8f, 9f))
        val fromSession = LeaderboardPoints.of(listOfNotNull(LeaderboardPoints.bestOf(workout)))
        val fromGhostScore = LeaderboardPoints.of(listOf(ExerciseBest(averageScore = 8.0, repCount = 3)))

        assertEquals(fromGhostScore, fromSession)
    }

    private fun session(scores: List<Float>) =
        WorkoutSession(
            id = "s1",
            exercise = ExerciseType.SQUAT,
            startedAt = 1_000L,
            endedAt = 2_000L,
            reps =
                scores.mapIndexed { index, score ->
                    RepScore(
                        repIndex = index + 1,
                        score = score,
                        tempoSeconds = 1.5f,
                        rangePercent = 90,
                        pauseSeconds = 0f,
                        reasons = emptyList(),
                    )
                },
        )
}
