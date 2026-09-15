package com.repmate.ui.home

import androidx.lifecycle.ViewModel
import com.repmate.data.repo.CalibrationRepository
import com.repmate.engine.ExerciseType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Used from `RepMateNavGraph` to decide, on an exercise-chip tap (or live_workout's own
 * pre-check), whether that exercise needs Calibration first or can go straight to Live Workout.
 * The same pattern as `com.repmate.ui.onboarding.OnboardingGateViewModel`: a thin wrapper so the
 * actual persistence question lives in [CalibrationRepository], not duplicated at each call site.
 *
 * Both Home's chip tap and live_workout's own entry check call [hasCalibrationProfile], so
 * neither one hardcodes its own "not calibrated" stub the way live_workout used to before this
 * existed.
 */
@HiltViewModel
class CalibrationGateViewModel
    @Inject
    constructor(
        private val calibrationRepository: CalibrationRepository,
    ) : ViewModel() {
        suspend fun hasCalibrationProfile(exerciseType: ExerciseType): Boolean = calibrationRepository.hasProfile(exerciseType)
    }
