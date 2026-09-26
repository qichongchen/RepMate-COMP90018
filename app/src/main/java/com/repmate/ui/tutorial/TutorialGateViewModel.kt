package com.repmate.ui.tutorial

import androidx.lifecycle.ViewModel
import com.repmate.engine.ExerciseType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Used from `RepMateNavGraph`'s shared `startExercise` to decide whether an exercise pick shows
 * [ExerciseTutorialDialog] before going on to the calibration-or-workout routing. Same pattern as
 * `com.repmate.ui.home.CalibrationGateViewModel`: a thin wrapper, so the persistence lives in
 * [TutorialPreferences] and every start path (Home's chips, Ghost Duel's "Start Workout to Beat
 * It") shares one gate instead of each screen carrying its own copy.
 */
@HiltViewModel
class TutorialGateViewModel
    @Inject
    constructor(
        private val tutorialPreferences: TutorialPreferences,
    ) : ViewModel() {
        /** Read once, at pick time: only the value when the user taps matters. */
        suspend fun hasOptedOutOfTutorial(exerciseType: ExerciseType): Boolean =
            tutorialPreferences.hasSeenTutorial(exerciseType).first()

        /** Called only for "Continue" with "Don't show this again" ticked -- never on cancel. */
        suspend fun optOutOfTutorial(exerciseType: ExerciseType) {
            tutorialPreferences.setHasSeenTutorial(exerciseType, seen = true)
        }
    }
