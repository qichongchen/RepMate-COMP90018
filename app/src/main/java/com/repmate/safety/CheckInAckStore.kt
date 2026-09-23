package com.repmate.safety

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the current safety check-in has already been answered ("I'm OK" tapped). Modelled as a
 * stored flag rather than a live clock or a race against [androidx.work.WorkManager] cancellation
 * alone: [CheckInEscalateWorker] re-checks this flag right before sending, so an "I'm OK" that
 * arrives while the escalate work is already running still stops the SMS, and tests can set the
 * flag directly instead of waiting out a real timer (see [CheckInEscalationActionTest] and
 * `CheckInEscalateWorkerTest`).
 *
 * A separate DataStore from [SafetyCheckInPreferences]: this is per-check-in transient state,
 * reset every time a new one is scheduled, not a user setting.
 */
interface CheckInAckStore {
    suspend fun clearAcknowledged()

    suspend fun markAcknowledged()

    suspend fun isAcknowledged(): Boolean
}

private val Context.checkInAckDataStore: DataStore<Preferences> by preferencesDataStore(name = "safety_check_in_ack")

@Singleton
class DataStoreCheckInAckStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CheckInAckStore {
        private val acknowledgedKey = booleanPreferencesKey("acknowledged")

        override suspend fun clearAcknowledged() {
            context.checkInAckDataStore.edit { it[acknowledgedKey] = false }
        }

        override suspend fun markAcknowledged() {
            context.checkInAckDataStore.edit { it[acknowledgedKey] = true }
        }

        override suspend fun isAcknowledged(): Boolean = context.checkInAckDataStore.data.first()[acknowledgedKey] ?: false
    }
