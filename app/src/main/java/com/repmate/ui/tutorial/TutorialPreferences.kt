package com.repmate.ui.tutorial

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.repmate.engine.ExerciseType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tutorialPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tutorial_prefs")

/**
 * Whether the user has opted out of [ExerciseTutorialDialog] auto-showing before a given exercise,
 * persisted across launches. Written only when the user ticks "Don't show this again" and then
 * taps the CTA (see [ExerciseTutorialDialog]'s `onContinue`); backing out never writes it.
 *
 * One independent flag per exercise, deliberately not tied to calibration: push-up has no
 * calibration step, so gating the tutorial on "not calibrated yet" would mean push-up never gets
 * one. Same shape and reasoning as `ThemePreferences`: device-wide (not per Firebase UID), local
 * Preferences DataStore, its own file ("tutorial_prefs"). Defaults to `false` -- unseen -- so the
 * tutorial always shows on a first attempt.
 */
@Singleton
class TutorialPreferences
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val hasSeenSquatTutorialKey = booleanPreferencesKey("has_seen_squat_tutorial")
        private val hasSeenPushupTutorialKey = booleanPreferencesKey("has_seen_pushup_tutorial")
        private val hasSeenJumpingJackTutorialKey = booleanPreferencesKey("has_seen_jumping_jack_tutorial")

        fun hasSeenTutorial(exerciseType: ExerciseType): Flow<Boolean> =
            context.tutorialPrefsDataStore.data.map { it[keyFor(exerciseType)] ?: false }

        suspend fun setHasSeenTutorial(
            exerciseType: ExerciseType,
            seen: Boolean,
        ) {
            context.tutorialPrefsDataStore.edit { it[keyFor(exerciseType)] = seen }
        }

        private fun keyFor(exerciseType: ExerciseType): Preferences.Key<Boolean> =
            when (exerciseType) {
                ExerciseType.SQUAT -> hasSeenSquatTutorialKey
                ExerciseType.PUSHUP -> hasSeenPushupTutorialKey
                ExerciseType.JUMPING_JACK -> hasSeenJumpingJackTutorialKey
            }
    }
