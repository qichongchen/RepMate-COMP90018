package com.repmate.safety

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The scheduling and cancellation logic the safety check-in feature depends on, tested against a
 * fake [CheckInWorkGateway]/[CheckInAckStore] instead of real WorkManager or DataStore -- neither
 * of which can do anything meaningful without a live Android context. See [CheckInWorkGateway]'s
 * own KDoc for why this is the seam.
 */
class CheckInSchedulerTest {
    private class FakeCheckInWorkGateway : CheckInWorkGateway {
        var scheduled: Pair<kotlin.time.Duration, kotlin.time.Duration>? = null
        var cancelCallCount = 0

        override fun scheduleCheckIn(
            notifyDelay: kotlin.time.Duration,
            escalateDelay: kotlin.time.Duration,
        ) {
            scheduled = notifyDelay to escalateDelay
        }

        override fun cancelCheckIn() {
            cancelCallCount++
        }
    }

    private class FakeCheckInAckStore : CheckInAckStore {
        var acknowledged = false
        var clearCallCount = 0

        override suspend fun clearAcknowledged() {
            clearCallCount++
            acknowledged = false
        }

        override suspend fun markAcknowledged() {
            acknowledged = true
        }

        override suspend fun isAcknowledged(): Boolean = acknowledged
    }

    @Test
    fun `scheduleAfterWorkout enqueues the default delays and clears the ack flag`() =
        runTest {
            val gateway = FakeCheckInWorkGateway()
            val ackStore = FakeCheckInAckStore()
            ackStore.acknowledged = true // stale from a previous check-in
            val scheduler = CheckInScheduler(gateway, ackStore)

            scheduler.scheduleAfterWorkout()

            assertEquals(CheckInScheduler.DEFAULT_CHECK_IN_DELAY to CheckInScheduler.DEFAULT_RESPONSE_WINDOW, gateway.scheduled)
            assertEquals(1, ackStore.clearCallCount)
            assertFalse(ackStore.acknowledged)
        }

    @Test
    fun `scheduleAfterWorkout passes through custom delays`() =
        runTest {
            val gateway = FakeCheckInWorkGateway()
            val scheduler = CheckInScheduler(gateway, FakeCheckInAckStore())

            scheduler.scheduleAfterWorkout(checkInDelay = 1.minutes, responseWindow = 2.minutes)

            assertEquals(1.minutes to 2.minutes, gateway.scheduled)
        }

    @Test
    fun `acknowledge marks the flag and cancels the gateway`() =
        runTest {
            val gateway = FakeCheckInWorkGateway()
            val ackStore = FakeCheckInAckStore()
            val scheduler = CheckInScheduler(gateway, ackStore)

            scheduler.acknowledge()

            assertTrue(ackStore.acknowledged)
            assertEquals(1, gateway.cancelCallCount)
        }

    @Test
    fun `disable only cancels the gateway, it does not touch the ack flag`() {
        val gateway = FakeCheckInWorkGateway()
        val ackStore = FakeCheckInAckStore()
        val scheduler = CheckInScheduler(gateway, ackStore)

        scheduler.disable()

        assertEquals(1, gateway.cancelCallCount)
        assertFalse(ackStore.acknowledged)
    }
}
