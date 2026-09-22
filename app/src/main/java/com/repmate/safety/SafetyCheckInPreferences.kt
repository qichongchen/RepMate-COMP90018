package com.repmate.safety

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.safetyCheckInDataStore: DataStore<Preferences> by preferencesDataStore(name = "safety_check_in_prefs")

/**
 * Whether safety check-in is on, and the one emergency contact it alerts. A single device-wide
 * setting, not keyed per Firebase UID -- same reasoning as [com.repmate.ui.theme.ThemePreferences]:
 * whose phone this is matters here, not which account is currently signed in on it.
 *
 * Backed by Preferences DataStore, same as `ThemePreferences`/`OnboardingPreferences`: purely
 * local, on-device settings with no reason to sync via Firestore.
 */
@Singleton
class SafetyCheckInPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val enabledKey = booleanPreferencesKey("enabled")
        private val contactNameKey = stringPreferencesKey("contact_name")
        private val contactPhoneKey = stringPreferencesKey("contact_phone")

        val isEnabled: Flow<Boolean> = context.safetyCheckInDataStore.data.map { it[enabledKey] ?: false }

        /** Null until a contact has been saved, or if either half was left blank. */
        val contact: Flow<SafetyContact?> =
            context.safetyCheckInDataStore.data.map { prefs ->
                val name = prefs[contactNameKey]
                val phone = prefs[contactPhoneKey]
                if (name.isNullOrBlank() || phone.isNullOrBlank()) null else SafetyContact(name, phone)
            }

        suspend fun setEnabled(enabled: Boolean) {
            context.safetyCheckInDataStore.edit { it[enabledKey] = enabled }
        }

        suspend fun setContact(contact: SafetyContact) {
            context.safetyCheckInDataStore.edit {
                it[contactNameKey] = contact.name
                it[contactPhoneKey] = contact.phoneNumber
            }
        }

        /** One-shot read for [CheckInEscalateWorker], which runs outside Compose's lifecycle-aware collection. */
        suspend fun contactSnapshot(): SafetyContact? = contact.first()

        /** One-shot read for the workout ViewModels deciding whether to schedule a check-in at all. */
        suspend fun isEnabledSnapshot(): Boolean = isEnabled.first()
    }
