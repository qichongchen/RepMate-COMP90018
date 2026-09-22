package com.repmate.safety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.repmate.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Posts (and clears) the "Are you okay?" notification [CheckInNotifyWorker] fires, and wires its
 * "I'm OK" action to [CheckInAckReceiver]. Kept out of the worker itself so the worker's own
 * `doWork()` stays a one-line call, matching this app's usual split between Android glue and the
 * logic behind it.
 */
class CheckInNotificationPresenter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun showAreYouOkNotification() {
            ensureChannel()

            // Golden Rule 7: a denied POST_NOTIFICATIONS permission must not crash this worker --
            // it just means nothing is shown, and the escalation still fires on schedule since it
            // was enqueued independently of whether this notification could be posted.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val ackIntent =
                Intent(context, CheckInAckReceiver::class.java).setAction(CheckInAckReceiver.ACTION_IM_OK)
            val ackPendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    ackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Are you okay?")
                    .setContentText("Tap \"I'm OK\", or your emergency contact gets a text.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .addAction(0, "I'm OK", ackPendingIntent)
                    .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        fun cancelNotification() {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        private fun ensureChannel() {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Safety check-in",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Asks if you're okay after a workout, before alerting your emergency contact."
                }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private companion object {
            const val CHANNEL_ID = "safety_check_in"
            const val NOTIFICATION_ID = 4201
        }
    }
