package com.repmate.ui.displayname

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.repmate.data.repo.UsernameRepository
import com.repmate.data.repo.needsDisplayNameGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Used from `RepMateNavGraph` (same pattern as `OnboardingGateViewModel`) to decide, right after a
 * sign-in, whether the user must choose a display name before going on. Guests never do; an
 * unknown answer (offline, timeout) lets the user through, and launch checks again.
 */
@HiltViewModel
class DisplayNameGateViewModel
    @Inject
    constructor(
        private val firebaseAuth: FirebaseAuth,
        private val repository: UsernameRepository,
    ) : ViewModel() {
        suspend fun needsDisplayName(): Boolean {
            val user = firebaseAuth.currentUser ?: return false
            if (user.isAnonymous) return false
            return needsDisplayNameGate(isAnonymous = false, status = repository.claimStatus())
        }
    }
