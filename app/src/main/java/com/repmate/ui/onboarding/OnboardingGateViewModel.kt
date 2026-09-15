package com.repmate.ui.onboarding

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Used once, from `RepMateNavGraph`, to decide whether a just-authenticated user (sign-up,
 * log-in, or guest) should land on onboarding or skip straight to home.
 *
 * All three "on success" callbacks in `NavGraph.kt` route through [hasCurrentUserSeenOnboarding]
 * rather than each hardcoding a destination, so a first-time log-in on a new device (a real case:
 * an existing account signing in somewhere it has never onboarded before) is treated the same as
 * a first-time sign-up or a brand-new guest session, instead of LogIn unconditionally skipping
 * onboarding the way it used to.
 */
@HiltViewModel
class OnboardingGateViewModel
    @Inject
    constructor(
        private val onboardingPreferences: OnboardingPreferences,
        private val firebaseAuth: FirebaseAuth,
    ) : ViewModel() {
        suspend fun hasCurrentUserSeenOnboarding(): Boolean {
            val uid = firebaseAuth.currentUser?.uid ?: return false
            return onboardingPreferences.hasCompletedOnboarding(uid)
        }
    }
