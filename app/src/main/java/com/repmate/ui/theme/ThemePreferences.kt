package com.repmate.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

/**
 * Whether [RepMateTheme] should render dark or light, persisted across launches. Unlike
 * `OnboardingPreferences`, this is a single device-wide flag, not keyed per Firebase UID: which
 * theme to show is a device preference, not something that should flip when a different account
 * signs in on the same phone.
 *
 * Backed by Preferences DataStore for the same reason as `OnboardingPreferences`: purely local,
 * on-device UI state with no reason to sync via Firestore. Defaults to `true` (dark) when unset,
 * matching [RepMateTheme]'s own hardcoded default before this preference existed.
 */
@Singleton
class ThemePreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val darkThemeEnabledKey = booleanPreferencesKey("dark_theme_enabled")

        val isDarkThemeEnabled: Flow<Boolean> =
            context.themeDataStore.data.map { it[darkThemeEnabledKey] ?: true }

        suspend fun setDarkThemeEnabled(enabled: Boolean) {
            context.themeDataStore.edit { it[darkThemeEnabledKey] = enabled }
        }
    }
