package com.repmate.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure label-choosing helpers behind [AxesCanvas]; the drawing itself is checked in the previews. */
class ChartAxesTest {
    // --- thinTickIndices ---------------------------------------------------------------------

    @Test
    fun noTicksAndASingleTickAreHandled() {
        assertEquals(emptyList<Int>(), thinTickIndices(count = 0, maxLabels = 5))
        assertEquals(listOf(0), thinTickIndices(count = 1, maxLabels = 5))
    }

    @Test
    fun everyLabelIsShownWhenTheyAllFit() {
        assertEquals(listOf(0, 1), thinTickIndices(count = 2, maxLabels = 5))
        assertEquals(listOf(0, 1, 2, 3, 4), thinTickIndices(count = 5, maxLabels = 5))
    }

    @Test
    fun everyKthLabelIsShownWhenTheyDoNotFit() {
        assertEquals(listOf(0, 2, 4, 6, 8), thinTickIndices(count = 9, maxLabels = 5))
        assertEquals(listOf(0, 3, 6, 9), thinTickIndices(count = 10, maxLabels = 5))
    }

    @Test
    fun aLabelTooCloseToTheLastOneIsDropped() {
        // Every 3rd would give 0, 3, 6, 9 and then 11, with 9 and 11 two apart (closer than the step).
        assertEquals(listOf(0, 3, 6, 11), thinTickIndices(count = 12, maxLabels = 5))
        assertEquals(listOf(0, 4, 8, 12, 19), thinTickIndices(count = 20, maxLabels = 6))
    }

    @Test
    fun twoLabelsAreTheMinimum() {
        assertEquals(listOf(0, 9), thinTickIndices(count = 10, maxLabels = 2))
        assertEquals(listOf(0, 9), thinTickIndices(count = 10, maxLabels = 1))
        assertEquals(listOf(0, 9), thinTickIndices(count = 10, maxLabels = 0))
    }

    @Test
    fun firstAndLastAreAlwaysShownAndLabelsNeverCrowd() {
        for (count in 2..80) {
            for (maxLabels in 2..14) {
                val shown = thinTickIndices(count, maxLabels)

                assertEquals("count=$count max=$maxLabels", 0, shown.first())
                assertEquals("count=$count max=$maxLabels", count - 1, shown.last())
                assertTrue("count=$count max=$maxLabels", shown.size <= maxLabels)
                assertEquals("sorted and distinct, count=$count max=$maxLabels", shown.sorted().distinct(), shown)

                // However they were chosen, two shown labels are never closer (in ticks) than the
                // spacing that makes maxLabels of them fit across the axis.
                if (count > maxLabels) {
                    val minGap = (count - 1 + maxLabels - 2) / (maxLabels - 1)
                    shown.zipWithNext().forEach { (a, b) ->
                        assertTrue("gap $a..$b, count=$count max=$maxLabels", b - a >= minGap)
                    }
                }
            }
        }
    }

    // --- timeTickMillis / timeTickStepMs / formatTimeTick -------------------------------------

    @Test
    fun timeTicksAreRoundNumbersFromZero() {
        assertEquals(listOf(0L, 500L, 1000L, 1500L), timeTickMillis(1800L))
        assertEquals(listOf(0L, 500L, 1000L), timeTickMillis(1100L))
        assertEquals(listOf(0L, 1000L, 2000L), timeTickMillis(2000L))
        assertEquals(listOf(0L, 200L, 400L, 600L), timeTickMillis(600L))
        assertEquals(listOf(0L, 250L, 500L, 750L), timeTickMillis(900L))
        assertEquals(listOf(0L, 5000L, 10000L), timeTickMillis(12000L))
    }

    @Test
    fun aZeroOrNegativeDurationHasJustTheOriginTick() {
        assertEquals(listOf(0L), timeTickMillis(0L))
        assertEquals(listOf(0L), timeTickMillis(-50L))
    }

    @Test
    fun timeTicksStayWithinTheDurationAndAreThreeOrFourForARealRep() {
        for (durationMs in 100L..60_000L step 37L) {
            val ticks = timeTickMillis(durationMs)
            val step = timeTickStepMs(durationMs)

            assertTrue("duration=$durationMs ticks=$ticks", ticks.size in 3..4)
            assertEquals(0L, ticks.first())
            assertTrue("duration=$durationMs", ticks.last() <= durationMs)
            assertEquals(ticks.indices.map { it * step }, ticks)
        }
    }

    @Test
    fun timeTickLabelsHaveJustEnoughDecimalsForTheirStep() {
        assertEquals("0.0", formatTimeTick(0L, stepMs = 500L))
        assertEquals("0.5", formatTimeTick(500L, stepMs = 500L))
        assertEquals("1.0", formatTimeTick(1000L, stepMs = 500L))
        assertEquals("1.5", formatTimeTick(1500L, stepMs = 500L))
        assertEquals("0", formatTimeTick(0L, stepMs = 1000L))
        assertEquals("2", formatTimeTick(2000L, stepMs = 1000L))
        assertEquals("0.25", formatTimeTick(250L, stepMs = 250L))
        assertEquals("0.50", formatTimeTick(500L, stepMs = 250L))
    }

    // --- formatAxisValue ---------------------------------------------------------------------

    @Test
    fun yAxisValuesHaveOneDecimalWithAPoint() {
        assertEquals("9.8", formatAxisValue(9.84f))
        assertEquals("3.0", formatAxisValue(3f))
        assertEquals("12.4", formatAxisValue(12.36f))
    }
}
