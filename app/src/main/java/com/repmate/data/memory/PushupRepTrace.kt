package com.repmate.data.memory

/**
 * One completed push-up, as the report draws it: the elbow angle through the rep, and how deep it
 * got.
 *
 * Lives beside [JustFinishedSessionStore] rather than in the push-up UI package because both the
 * workout screen (which produces it) and the replay screen (which draws it) depend on it, and
 * neither should depend on the other.
 *
 * @property repIndex 1-based, matching the rep numbering the workout screen counted out.
 * @property curve `(millisecondsFromThisRepsStart, degrees)`, oldest first. Rebased to 0 so each
 *   rep's chart starts at its own beginning, the same way `rebaseCurve` does for the IMU replay.
 * @property bottomDegrees the lowest smoothed angle reached, i.e. how deep the rep went, or null
 *   if the detector could not read one.
 */
data class PushupRepTrace(
    val repIndex: Int,
    val curve: List<Pair<Long, Float>>,
    val bottomDegrees: Double?,
)
