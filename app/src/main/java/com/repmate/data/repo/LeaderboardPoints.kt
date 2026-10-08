package com.repmate.data.repo

import com.repmate.engine.WorkoutSession
import kotlin.math.roundToInt

/**
 * One exercise's best effort, reduced to the two numbers the leaderboard scores on.
 *
 * Deliberately not a [WorkoutSession]: the friends leaderboard builds these from the published
 * `ghostScores` documents (which hold only these two numbers plus the exercise and a timestamp),
 * while the global producer builds them from local sessions. Both feed [LeaderboardPoints.of], so
 * the two tabs can never disagree about what a point is.
 */
data class ExerciseBest(
    val averageScore: Double,
    val repCount: Int,
)

/**
 * How a leaderboard score is calculated, in one place.
 *
 * ## The rule
 * For each exercise, take the user's **best session** and multiply its average rep score (0-10)
 * by how many reps it was; sum that across the exercises, and round. A 10-rep squat set averaging
 * 8.0 is 80 points; adding a 12-rep jumping-jack set averaging 7.5 brings the total to 170.
 *
 * ## Why this rule
 * Form score alone would put a careful three-rep set above a careful thirty-rep one, and rep
 * count alone would reward flailing. Multiplying them means points only go up by doing *more* reps
 * *well*, which is what the app is for. Using the best session per exercise rather than a lifetime
 * total keeps the number stable and comparable: it is the same "best per exercise" that Ghost Duel
 * already publishes, so one bad set can never cost someone points.
 *
 * It is a *project* scoring rule, not a universal one. If the team wants to weight exercises
 * differently, or count every session instead of the best, this is the only function to change.
 */
object LeaderboardPoints {
    /** The leaderboard score for a user whose best efforts are [bests]. Empty means 0. */
    fun of(bests: List<ExerciseBest>): Int = bests.sumOf { pointsFor(it) }

    /** One exercise's contribution: average score x reps, rounded. Never negative. */
    fun pointsFor(best: ExerciseBest): Int =
        if (best.repCount <= 0 || best.averageScore <= 0.0) {
            0
        } else {
            (best.averageScore * best.repCount).roundToInt()
        }

    /**
     * [session] as an [ExerciseBest], or null when it has no reps to score.
     *
     * The average is over the session's own rep scores, matching what
     * `FirestoreGhostScoreDataSource` publishes for the same session, so a user's points come out
     * the same whether they were computed from local history or read back from `ghostScores`.
     */
    fun bestOf(session: WorkoutSession): ExerciseBest? {
        if (session.reps.isEmpty()) return null
        return ExerciseBest(
            averageScore = session.reps.map { it.score.toDouble() }.average(),
            repCount = session.reps.size,
        )
    }
}
