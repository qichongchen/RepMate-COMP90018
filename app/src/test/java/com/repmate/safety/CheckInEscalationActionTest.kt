package com.repmate.safety

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The escalation decision: send unless already acknowledged, and degrade to "no location" rather
 * than fail when one can't be obtained. Pulled out of [CheckInEscalateWorker] specifically so this
 * can be tested with fakes -- see that class's KDoc.
 */
class CheckInEscalationActionTest {
    private class FakeCheckInAckStore(
        private var acknowledged: Boolean,
    ) : CheckInAckStore {
        override suspend fun clearAcknowledged() {
            acknowledged = false
        }

        override suspend fun markAcknowledged() {
            acknowledged = true
        }

        override suspend fun isAcknowledged(): Boolean = acknowledged
    }

    private class FakeLastLocationProvider(
        private val location: LastLocation?,
    ) : LastLocationProvider {
        override suspend fun lastKnownLocation(): LastLocation? = location
    }

    private class FakeSafetyAlertSender : SafetyAlertSender {
        val sentAlerts = mutableListOf<Pair<SafetyContact, LastLocation?>>()

        override suspend fun sendCheckInAlert(
            contact: SafetyContact,
            location: LastLocation?,
        ) {
            sentAlerts += contact to location
        }
    }

    private val contact = SafetyContact(name = "Riley", phoneNumber = "+61400000000")

    @Test
    fun `sends the alert with the last known location when not acknowledged`() =
        runTest {
            val location = LastLocation(latitude = -37.8, longitude = 144.9)
            val alertSender = FakeSafetyAlertSender()
            val action = CheckInEscalationAction(FakeCheckInAckStore(acknowledged = false), FakeLastLocationProvider(location), alertSender)

            val sent = action.run(contact)

            assertTrue(sent)
            assertEquals(listOf<Pair<SafetyContact, LastLocation?>>(contact to location), alertSender.sentAlerts)
        }

    @Test
    fun `sends the alert without a location when none is available`() =
        runTest {
            val alertSender = FakeSafetyAlertSender()
            val action = CheckInEscalationAction(FakeCheckInAckStore(acknowledged = false), FakeLastLocationProvider(null), alertSender)

            val sent = action.run(contact)

            assertTrue(sent)
            assertEquals(1, alertSender.sentAlerts.size)
            assertNull(alertSender.sentAlerts.single().second)
        }

    @Test
    fun `does not send when already acknowledged`() =
        runTest {
            val alertSender = FakeSafetyAlertSender()
            val action =
                CheckInEscalationAction(
                    FakeCheckInAckStore(acknowledged = true),
                    FakeLastLocationProvider(LastLocation(0.0, 0.0)),
                    alertSender,
                )

            val sent = action.run(contact)

            assertFalse(sent)
            assertTrue(alertSender.sentAlerts.isEmpty())
        }
}
