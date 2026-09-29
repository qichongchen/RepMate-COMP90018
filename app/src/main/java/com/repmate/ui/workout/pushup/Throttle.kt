package com.repmate.ui.workout.pushup

/**
 * Lets something happen at most once per [intervalMs] of a clock that only moves forward.
 *
 * The first call is always ready. That is the point of keeping the last time as a nullable rather
 * than starting it at `Long.MIN_VALUE`: `now - Long.MIN_VALUE` overflows to a negative number, which
 * is never at least an interval, so the thing being throttled would never happen at all.
 */
class Throttle(private val intervalMs: Long) {
    private var lastMs: Long? = null

    /** True if enough time has passed since the last true, in which case [nowMs] is recorded. */
    fun ready(nowMs: Long): Boolean {
        val last = lastMs
        if (last != null && nowMs - last < intervalMs) return false
        lastMs = nowMs
        return true
    }
}
