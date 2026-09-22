package com.repmate.safety

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * First stage of the check-in chain: posts the "Are you okay?" notification via
 * [CheckInNotificationPresenter]. Deliberately one line -- the only thing worth testing here is
 * that [CheckInNotificationPresenter] gets called, which the KDoc-adjacent worker test does
 * directly; there is no decision logic to unit-test the way [CheckInEscalationAction] has.
 */
@HiltWorker
class CheckInNotifyWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val presenter: CheckInNotificationPresenter,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            presenter.showAreYouOkNotification()
            return Result.success()
        }
    }
