package com.repmate.data.local

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

private val Context.displayNameDataStore: DataStore<Preferences> by preferencesDataStore(name = "display_name_prefs")

/**
 * Remembers, per Firebase UID, that the user has claimed a unique display name, so launch does
 * not need the network to decide whether to show the choose-name screen.
 *
 * Only the positive result is stored: "not claimed" can go stale (the user may claim on another
 * device), "claimed" is stable. NOTE: because of that, a test account whose claim is deleted by
 * hand in the Firebase console keeps this flag until app data is cleared.
 */
@Singleton
class DisplayNamePreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private fun keyFor(uid: String) = booleanPreferencesKey("name_claimed_$uid")

        suspend fun isClaimed(uid: String): Boolean = context.displayNameDataStore.data.first()[keyFor(uid)] ?: false

        suspend fun markClaimed(uid: String) {
            context.displayNameDataStore.edit { it[keyFor(uid)] = true }
        }
    }
