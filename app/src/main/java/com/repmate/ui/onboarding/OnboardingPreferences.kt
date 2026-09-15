package com.repmate.ui.onboarding

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

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "onboarding_prefs")

/**
 * Tracks whether onboarding has been shown, keyed per Firebase UID, not per device and not
 * globally. Two different accounts (or two separate guest sessions, each with its own anonymous
 * UID from `signInAnonymously()`) sharing one device must each see onboarding once,
 * independently: a single device-wide flag would wrongly skip it for the second person to use
 * the phone, guest or not.
 *
 * Backed by Preferences DataStore, not Firestore: this is purely local, on-device UI state that
 * has no reason to sync across a user's devices or survive a reinstall, the same reasoning that
 * puts other local-only config in `local.properties`/`BuildConfig` rather than a backend.
 */
@Singleton
class OnboardingPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private fun keyFor(uid: String) = booleanPreferencesKey("onboarded_$uid")

        suspend fun hasCompletedOnboarding(uid: String): Boolean = context.onboardingDataStore.data.first()[keyFor(uid)] ?: false

        suspend fun markOnboardingCompleted(uid: String) {
            context.onboardingDataStore.edit { it[keyFor(uid)] = true }
        }
    }
