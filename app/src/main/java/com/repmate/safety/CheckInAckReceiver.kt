package com.repmate.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "I'm OK" action tapped on the notification [CheckInNotificationPresenter] posts.
 * Declared (not exported) in the manifest rather than registered dynamically -- it must still
 * receive the tap if the app process isn't running, which only a manifest-declared receiver can.
 */
@AndroidEntryPoint
class CheckInAckReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: CheckInScheduler

    @Inject
    lateinit var presenter: CheckInNotificationPresenter

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_IM_OK) return

        Log.i(TAG, "\"I'm OK\" tapped; cancelling the pending check-in")
        presenter.cancelNotification()

        // goAsync(): onReceive() itself can't suspend, but the process is otherwise free to be
        // killed the moment it returns, before CheckInScheduler.acknowledge()'s DataStore write
        // and WorkManager.cancelUniqueWork() actually land -- goAsync()'s PendingResult tells the
        // system to keep this receiver alive until finish() is called.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scheduler.acknowledge()
                Log.i(TAG, "check-in acknowledged and cancelled")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_IM_OK = "com.repmate.safety.ACTION_IM_OK"
        private const val TAG = "SafetyCheckIn"
    }
}
