package com.repmate.safety

import javax.inject.Inject

/**
 * What [CheckInEscalateWorker] actually decides, pulled out of the `CoroutineWorker` so it can be
 * unit-tested with fakes instead of a real device (see `CheckInEscalationActionTest`) -- the
 * worker itself is left as thin, untested Android glue, the same split this app already uses for
 * `RepFeedback` versus the ViewModels that call it.
 *
 * [contact] is passed in rather than read from [SafetyCheckInPreferences] here, so this class
 * doesn't need a DataStore-backed dependency to be constructed in a test.
 */
class CheckInEscalationAction
    @Inject
    constructor(
        private val ackStore: CheckInAckStore,
        private val locationProvider: LastLocationProvider,
        private val alertSender: SafetyAlertSender,
    ) {
        /**
         * Sends the alert unless [CheckInAckStore] already shows "I'm OK" was tapped. Checked
         * here, not just relied on via `WorkManager.cancelUniqueWork`, to close the race where
         * the acknowledgement arrives after this work has already started running (cancellation
         * only stops work that hasn't started, or cooperatively signals work already in flight --
         * this flag check is the actual guarantee).
         *
         * @return true if an alert was sent, false if skipped because the user already confirmed
         *   they're OK -- callers use this only for logging, not to decide anything further.
         */
        suspend fun run(contact: SafetyContact): Boolean {
            if (ackStore.isAcknowledged()) return false
            val location = locationProvider.lastKnownLocation()
            alertSender.sendCheckInAlert(contact, location)
            return true
        }
    }
