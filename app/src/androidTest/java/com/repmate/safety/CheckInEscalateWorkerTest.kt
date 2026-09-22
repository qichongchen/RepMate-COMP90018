package com.repmate.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real [CheckInEscalateWorker.doWork] via [TestListenableWorkerBuilder], with a
 * [WorkerFactory] that hands it fakes instead of Hilt's real dependencies -- this needs a real
 * Android [Context] for `TestListenableWorkerBuilder`'s internals, which is why it lives here
 * under androidTest rather than alongside [CheckInEscalationActionTest] in the JVM unit tests
 * (run with `./gradlew connectedDebugAndroidTest`, a real device/emulator, not `testDebugUnitTest`).
 *
 * The escalation *decision* itself ([CheckInEscalationAction]) already has full JVM-unit-test
 * coverage; what this test adds is confidence that the worker actually wires that decision up --
 * reads the saved contact, and skips cleanly when there is none.
 */
@RunWith(AndroidJUnit4::class)
class CheckInEscalateWorkerTest {
    private class FakeCheckInAckStore : CheckInAckStore {
        override suspend fun clearAcknowledged() {}

        override suspend fun markAcknowledged() {}

        override suspend fun isAcknowledged(): Boolean = false
    }

    private class FakeLastLocationProvider : LastLocationProvider {
        override suspend fun lastKnownLocation(): LastLocation? = LastLocation(latitude = -37.8, longitude = 144.9)
    }

    private class FakeSafetyAlertSender : SafetyAlertSender {
        val sentAlerts = mutableListOf<SafetyContact>()

        override suspend fun sendCheckInAlert(
            contact: SafetyContact,
            location: LastLocation?,
        ) {
            sentAlerts += contact
        }
    }

    private fun workerFactoryFor(
        action: CheckInEscalationAction,
        preferences: SafetyCheckInPreferences,
    ) = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = CheckInEscalateWorker(appContext, workerParameters, preferences, action)
    }

    @Test
    fun sendsAlertToTheSavedContact() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val contact = SafetyContact(name = "Riley", phoneNumber = "+61400000000")
            val preferences = SafetyCheckInPreferences(context)
            preferences.setContact(contact)

            val alertSender = FakeSafetyAlertSender()
            val action = CheckInEscalationAction(FakeCheckInAckStore(), FakeLastLocationProvider(), alertSender)

            val worker =
                TestListenableWorkerBuilder<CheckInEscalateWorker>(context)
                    .setWorkerFactory(workerFactoryFor(action, preferences))
                    .build()

            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(listOf(contact), alertSender.sentAlerts)
        }

    @Test
    fun skipsCleanlyWhenNoContactIsSaved() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = SafetyCheckInPreferences(context)
            // Force the "no contact" state explicitly rather than relying on this being a fresh
            // DataStore file -- JUnit doesn't guarantee this runs before sendsAlertToTheSavedContact,
            // which saves a real one to the same on-device file.
            preferences.setContact(SafetyContact(name = "", phoneNumber = ""))

            val alertSender = FakeSafetyAlertSender()
            val action = CheckInEscalationAction(FakeCheckInAckStore(), FakeLastLocationProvider(), alertSender)

            val worker =
                TestListenableWorkerBuilder<CheckInEscalateWorker>(context)
                    .setWorkerFactory(workerFactoryFor(action, preferences))
                    .build()

            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
        }
}
