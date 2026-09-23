package com.repmate.safety

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Sends the escalation alert to [SafetyContact.phoneNumber], seamed off behind an interface for
 * the same reason as [LastLocationProvider]: [SmsManager] needs a real device/permission to do
 * anything, so [CheckInEscalationAction] is tested against a fake instead.
 */
interface SafetyAlertSender {
    suspend fun sendCheckInAlert(
        contact: SafetyContact,
        location: LastLocation?,
    )
}

class SmsSafetyAlertSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SafetyAlertSender {
        override suspend fun sendCheckInAlert(
            contact: SafetyContact,
            location: LastLocation?,
        ) {
            val message = buildMessage(location)
            try {
                val smsManager =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                // divideMessage + sendMultipartTextMessage, not sendTextMessage: the message
                // (contact reminder + maps link) can run past the single-SMS 160-character limit,
                // and sendTextMessage silently truncates/mangles anything longer than that.
                val parts = smsManager.divideMessage(message)
                Log.i(TAG, "sending safety check-in alert (${parts.size} part(s))")
                // sendMultipartTextMessage itself only queues the request -- it does not throw or
                // return anything for "no SIM"/"radio off"/carrier rejection, those only surface
                // asynchronously via each part's sentIntent, which is otherwise easy to leave null
                // and end up with zero visibility into whether the alert actually went anywhere.
                val sentIntents = ArrayList(parts.indices.map { registerOneShotSentReceiver(it) })
                smsManager.sendMultipartTextMessage(contact.phoneNumber, null, parts, sentIntents, null)
            } catch (e: SecurityException) {
                // SEND_SMS was revoked after the feature was enabled (e.g. from system Settings).
                // Golden Rule 7: this must not crash the escalate worker, just fail to alert --
                // there is no UI visible right now to surface this to, since the user is
                // (as far as this code knows) unresponsive.
                Log.w(TAG, "SEND_SMS permission missing; safety check-in alert not sent", e)
            }
        }

        /** One-shot: logs that single part's send outcome, then unregisters itself. Not awaited --
         * [sendCheckInAlert] doesn't block on delivery confirmation, only on the send call itself. */
        private fun registerOneShotSentReceiver(partIndex: Int): PendingIntent {
            val action = "com.repmate.safety.SMS_SENT.$partIndex.${System.nanoTime()}"
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: Intent,
                    ) {
                        val outcome =
                            when (resultCode) {
                                Activity.RESULT_OK -> "sent"
                                SmsManager.RESULT_ERROR_NO_SERVICE -> "failed: no service (no SIM, or no signal)"
                                SmsManager.RESULT_ERROR_RADIO_OFF -> "failed: radio off (airplane mode?)"
                                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "failed: generic failure"
                                SmsManager.RESULT_ERROR_NULL_PDU -> "failed: null PDU"
                                else -> "failed: result code $resultCode"
                            }
                        Log.i(TAG, "safety check-in alert part $partIndex: $outcome")
                        receiverContext.unregisterReceiver(this)
                    }
                }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val intent = Intent(action).setPackage(context.packageName)
            return PendingIntent.getBroadcast(context, partIndex, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun buildMessage(location: LastLocation?): String {
            val locationPart =
                location?.let { "Last known location: https://maps.google.com/?q=${it.latitude},${it.longitude}" }
                    ?: "Location unavailable."
            return "RepMate safety check-in: this person didn't confirm they're OK after a workout. $locationPart"
        }

        private companion object {
            const val TAG = "SafetyCheckIn"
        }
    }
