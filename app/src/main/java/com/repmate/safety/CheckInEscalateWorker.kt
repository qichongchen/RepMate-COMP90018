package com.repmate.safety

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Second stage of the check-in chain, only reached (via [CheckInWorkGateway]'s `.then()`) once
 * [CheckInNotifyWorker] has run. Reads the contact and hands off to [CheckInEscalationAction] for
 * the actual "should this send, and to where" decision -- kept out of this class so that logic is
 * unit-testable with fakes (see `CheckInEscalationActionTest`); this class itself is exercised
 * with `TestListenableWorkerBuilder` in `CheckInEscalateWorkerTest`, which needs a real Android
 * runtime for its `Context`/`WorkerParameters` and so lives under `androidTest`.
 */
@HiltWorker
class CheckInEscalateWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val preferences: SafetyCheckInPreferences,
        private val action: CheckInEscalationAction,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val contact = preferences.contactSnapshot()
            if (contact == null) {
                // Enabled with no contact saved shouldn't be reachable from Profile, but degrade
                // gracefully rather than crash a background worker over it (Golden Rule 7).
                Log.w(TAG, "safety check-in escalated with no emergency contact saved; nothing to alert")
                return Result.success()
            }
            action.run(contact)
            return Result.success()
        }

        private companion object {
            const val TAG = "SafetyCheckIn"
        }
    }
