package com.repmate.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [OnboardingScreen]. Its one real job is marking onboarding complete for the current user
 * on finish (Skip, or "Get started" on the last page). The pager itself is pure UI scroll state,
 * owned by the Composable via `rememberPagerState`, not hoisted here.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val onboardingPreferences: OnboardingPreferences,
        private val firebaseAuth: FirebaseAuth,
    ) : ViewModel() {
        private val _finished = Channel<Unit>(Channel.BUFFERED)

        /**
         * Fires once onboarding is marked complete for the current user. A [Channel], not a
         * plain boolean, for the same reason as `AuthViewModel.authSucceeded`: a re-collected
         * flag shouldn't be able to re-fire navigation.
         */
        val finished = _finished.receiveAsFlow()

        fun onFinishClicked() {
            viewModelScope.launch {
                // Onboarding is only ever reached right after some sign-in has already
                // succeeded, so currentUser should never actually be null here, but if it
                // somehow were, degrading to "just finish without persisting the flag" (Golden
                // Rule 7) beats crashing a screen whose entire job is to get out of the way.
                firebaseAuth.currentUser?.uid?.let { uid -> onboardingPreferences.markOnboardingCompleted(uid) }
                _finished.send(Unit)
            }
        }
    }
