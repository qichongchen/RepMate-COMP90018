package com.repmate.ui.history

import com.repmate.engine.WorkoutSession
import com.repmate.ui.components.displayLabel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row [HistoryScreen] renders in a card -- already display-formatted, no [WorkoutSession] left to unpack. */
data class HistorySessionUi(
    val id: String,
    val exercise: String,
    val repCount: Int,
    val averageScore: Float,
    val dateLabel: String,
    val durationLabel: String?,
)

/** A calendar-month bucket of [HistorySessionUi] (e.g. "September 2026"), newest session first. */
data class HistorySection(
    val label: String,
    val sessions: List<HistorySessionUi>,
)

data class HistoryUiState(
    val sections: List<HistorySection> = emptyList(),
    val isLoading: Boolean = true,
)

/** e.g. "Wed 10 Sep" -- shared by History's cards and Session Detail's header. */
internal val HISTORY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/**
 * The average of a session's rep scores, 0 when there are none -- the same computation
 * [com.repmate.data.cloud.FirestoreWorkoutDataSource.upload] already performs when it uploads a
 * session's `averageScore` field. Not shared with that class directly (data/ and cloud/ are out
 * of scope for this feature); duplicated as the one-liner it is rather than introducing a
 * cross-layer dependency for it.
 */
internal fun WorkoutSession.averageScore(): Float =
    (if (reps.isEmpty()) 0.0 else reps.map { it.score.toDouble() }.average()).toFloat()

/** "3:12"-style duration, or null when there's nothing honest to show. */
internal fun WorkoutSession.durationLabel(): String? {
    val endedAt = endedAt ?: return null
    val durationMs = endedAt - startedAt
    if (durationMs <= 0) return null
    val totalSeconds = durationMs / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

internal fun WorkoutSession.toHistorySessionUi(zoneId: ZoneId): HistorySessionUi =
    HistorySessionUi(
        id = id,
        exercise = exercise.displayLabel(),
        repCount = reps.size,
        averageScore = averageScore(),
        dateLabel = Instant.ofEpochMilli(startedAt).atZone(zoneId).format(HISTORY_DATE_FORMATTER),
        durationLabel = durationLabel(),
    )

/** e.g. "September 2026". */
private val MONTH_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/** The (year, month) [groupSessionsIntoSections] buckets sessions by. [YearMonth] compares by calendar year/month directly, so this is correct across a year boundary with no extra handling needed. */
private fun monthKeyOf(zonedDateTime: ZonedDateTime): YearMonth = YearMonth.from(zonedDateTime)

/**
 * Buckets [sessions] by calendar month (e.g. "September 2026"), omitting a bucket entirely when
 * it has no sessions -- every month gets the same plain label, there is no "this month"
 * special case to keep fresh. [sessions] is assumed newest-first, as
 * [com.repmate.data.repo.SessionRepository.recent] returns it -- each bucket is built by
 * filtering, not re-sorting, so that ordering carries through unchanged, and since the months
 * are collected in the order they first appear in [sessions], the sections themselves come out
 * newest month first too.
 *
 * @param zoneId the zone each session's [WorkoutSession.startedAt] (a UTC instant) is read back
 *   as a wall-clock date in; defaults to the device's own zone.
 * @param now unused now that bucketing no longer special-cases "this month" against the current
 *   time; kept in the signature (with its deterministic-test override) so callers and tests
 *   don't have to change if a future grouping rule needs "now" again.
 */
fun groupSessionsIntoSections(
    sessions: List<WorkoutSession>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    now: ZonedDateTime = ZonedDateTime.now(zoneId),
): List<HistorySection> {
    val monthKeys = sessions.map { monthKeyOf(Instant.ofEpochMilli(it.startedAt).atZone(zoneId)) }.distinct()

    return monthKeys.mapNotNull { monthKey ->
        sessions
            .filter { monthKeyOf(Instant.ofEpochMilli(it.startedAt).atZone(zoneId)) == monthKey }
            .toSectionOrNull(monthKey.format(MONTH_LABEL_FORMATTER), zoneId)
    }
}

private fun List<WorkoutSession>.toSectionOrNull(label: String, zoneId: ZoneId): HistorySection? =
    takeIf { it.isNotEmpty() }?.let { HistorySection(label, it.map { session -> session.toHistorySessionUi(zoneId) }) }
