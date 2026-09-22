package com.repmate.safety

import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The safety check-in feature's entry point from the rest of the app: workout ViewModels call
 * [scheduleAfterWorkout] once a session is saved, [CheckInAckReceiver] calls [acknowledge] when
 * "I'm OK" is tapped, and [com.repmate.ui.profile.ProfileViewModel] calls [disable] when the
 * feature is turned off. Everything WorkManager-specific lives behind [CheckInWorkGateway], which
 * is what [CheckInSchedulerTest] fakes.
 */
class CheckInScheduler
    @Inject
    constructor(
        private val gateway: CheckInWorkGateway,
        private val ackStore: CheckInAckStore,
    ) {
        suspend fun scheduleAfterWorkout(
            checkInDelay: Duration = DEFAULT_CHECK_IN_DELAY,
            responseWindow: Duration = DEFAULT_RESPONSE_WINDOW,
        ) {
            // Cleared before enqueueing, not after: this check-in hasn't been answered yet, and a
            // stale `true` left over from a previous one must not silently skip this one's alert.
            ackStore.clearAcknowledged()
            gateway.scheduleCheckIn(checkInDelay, responseWindow)
        }

        /**
         * "I'm OK". Marks the flag *and* cancels the WorkManager chain -- the flag is the
         * authoritative guard [CheckInEscalationAction] re-checks even if the escalate work is
         * already running when this is called; the cancel is what stops a chain that hasn't
         * started that stage yet from ever running it at all.
         */
        suspend fun acknowledge() {
            ackStore.markAcknowledged()
            gateway.cancelCheckIn()
        }

        /** Turning the feature off in Profile: stop any check-in this device still has pending. */
        fun disable() {
            gateway.cancelCheckIn()
        }

        companion object {
            /** "after N minutes (configurable, default 5)". */
            val DEFAULT_CHECK_IN_DELAY = 5.minutes

            /** "a further window (default 5 min)". */
            val DEFAULT_RESPONSE_WINDOW = 5.minutes
        }
    }
