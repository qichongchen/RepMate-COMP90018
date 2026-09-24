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

private val Context.workoutPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "workout_prefs")

/**
 * Whether the per-rep side effects in `com.repmate.ui.workout.RepFeedback` -- the haptic buzz and
 * the spoken rep count -- should fire, persisted across launches. Written only from
 * `ProfileScreen`; the workout and calibration screens only read it, since none of them has a
 * toggle of its own to write back from.
 *
 * Same shape and reasoning as [ThemePreferences]: a device-wide flag (not keyed per Firebase UID),
 * backed by Preferences DataStore because it's purely local UI state with no reason to sync. Its
 * own DataStore file ("workout_prefs") keeps it from colliding with "theme_prefs".
 *
 * Defaults (haptic on, spoken off) match `ProfileUiState`'s placeholder values from before this
 * store existed, so the toggles don't visibly jump the first time a user opens Profile. Any
 * `stateIn` initial value read from these flows must use the same defaults.
 */
@Singleton
class WorkoutPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val hapticFeedbackEnabledKey = booleanPreferencesKey("haptic_feedback_enabled")
        private val spokenRepCountEnabledKey = booleanPreferencesKey("spoken_rep_count_enabled")

        val isHapticFeedbackEnabled: Flow<Boolean> =
            context.workoutPrefsDataStore.data.map { it[hapticFeedbackEnabledKey] ?: true }

        val isSpokenRepCountEnabled: Flow<Boolean> =
            context.workoutPrefsDataStore.data.map { it[spokenRepCountEnabledKey] ?: false }

        suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
            context.workoutPrefsDataStore.edit { it[hapticFeedbackEnabledKey] = enabled }
        }

        suspend fun setSpokenRepCountEnabled(enabled: Boolean) {
            context.workoutPrefsDataStore.edit { it[spokenRepCountEnabledKey] = enabled }
        }
    }
