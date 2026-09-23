package com.repmate.safety

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The WorkManager calls [CheckInScheduler] needs, seamed off behind this app's usual
 * interface-at-the-boundary pattern so [CheckInScheduler]'s own scheduling/cancellation decisions
 * can be unit-tested against a fake `WorkManager` instance (real `WorkManager` needs a live
 * Android context to do anything, even to build a `WorkRequest` against).
 */
interface CheckInWorkGateway {
    /**
     * Enqueues the two-stage chain: show the "are you okay" notification after [notifyDelay],
     * then -- only if [CheckInAckStore] still shows unanswered by then -- escalate to SMS after a
     * further [escalateDelay]. Chained with `.then()`, not two independently-delayed requests, so
     * [escalateDelay] is measured from when the notification actually posted, not from schedule
     * time -- matching the spec's "a further window" wording.
     *
     * Uses [ExistingWorkPolicy.REPLACE]: starting a new check-in (a new workout ending) always
     * supersedes whatever check-in chain, if any, was still pending from a previous one.
     */
    fun scheduleCheckIn(
        notifyDelay: Duration,
        escalateDelay: Duration,
    )

    /** Cancels the whole chain, wherever it currently is -- both stages share one unique work name. */
    fun cancelCheckIn()
}

class WorkManagerCheckInGateway
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : CheckInWorkGateway {
        override fun scheduleCheckIn(
            notifyDelay: Duration,
            escalateDelay: Duration,
        ) {
            val notifyRequest =
                OneTimeWorkRequestBuilder<CheckInNotifyWorker>()
                    .setInitialDelay(notifyDelay.toJavaDuration())
                    .build()
            val escalateRequest =
                OneTimeWorkRequestBuilder<CheckInEscalateWorker>()
                    .setInitialDelay(escalateDelay.toJavaDuration())
                    .build()

            workManager
                .beginUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, notifyRequest)
                .then(escalateRequest)
                .enqueue()
        }

        override fun cancelCheckIn() {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private companion object {
            const val UNIQUE_WORK_NAME = "safety_check_in"
        }
    }
