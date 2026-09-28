package com.repmate.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoldCounterTest {

    @Test
    fun `a single hold is released to zero`() {
        val holds = HoldCounter()
        holds.acquire()
        assertTrue(holds.release())
    }

    @Test
    fun `an outgoing screen releasing after the incoming one acquired does not turn it off`() {
        val holds = HoldCounter()
        holds.acquire() // calibration screen
        holds.acquire() // workout screen starts before calibration is disposed
        assertFalse(holds.release(), "the flag was cleared under the screen that still needs it")
        assertTrue(holds.release())
    }

    @Test
    fun `releasing with nothing held changes nothing and never reports last`() {
        val holds = HoldCounter()
        assertFalse(holds.release())
        holds.acquire()
        assertTrue(holds.release(), "a stray release must not push the count negative")
    }
}
