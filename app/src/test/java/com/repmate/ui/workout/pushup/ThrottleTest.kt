package com.repmate.ui.workout.pushup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThrottleTest {

    @Test
    fun `the first call is ready whatever the clock reads`() {
        for (now in listOf(0L, 1L, 1_355_491_429L, Long.MAX_VALUE / 2)) {
            assertTrue(Throttle(200).ready(now), "not ready on first call at $now")
        }
    }

    @Test
    fun `calls inside the interval are refused and the interval is measured from the last ready`() {
        val t = Throttle(200)
        assertTrue(t.ready(1_000))
        assertFalse(t.ready(1_100))
        assertFalse(t.ready(1_199))
        assertTrue(t.ready(1_200))
        assertFalse(t.ready(1_300)) // measured from 1_200, not from 1_000
        assertTrue(t.ready(1_400))
    }
}
