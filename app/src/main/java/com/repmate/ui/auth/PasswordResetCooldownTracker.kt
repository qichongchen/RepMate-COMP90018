package com.repmate.ui.auth

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the wall-clock time (and email) of the last password-reset email sent, so the resend
 * cooldown on [ForgotPasswordScreen] survives leaving that screen and coming back to it.
 *
 * Why this can't just live on [ForgotPasswordViewModel] or its `SavedStateHandle`: Compose
 * Navigation scopes a `hiltViewModel()` to its NavBackStackEntry. Tapping back off
 * ForgotPasswordScreen pops that entry, which destroys the ViewModel *and* its SavedStateHandle
 * outright -- not just pauses them. Re-opening "Forgot password?" from LogIn afterward creates a
 * brand-new entry and a brand-new ViewModel with a blank `SavedStateHandle`, so anything stored
 * only there is gone. This tracker is `@Singleton`-scoped instead -- the Hilt application
 * component's lifetime, not any one screen visit -- specifically so a user bouncing back and
 * forth between LogIn and this screen can't restart the cooldown and spam
 * `sendPasswordResetEmail` past Firebase's rate limit.
 *
 * Deliberately in-memory only, not persisted to disk: this is a client-side courtesy on top of
 * Firebase's own server-side rate limiting, not a security boundary, so it's fine for a fresh
 * process start to clear it -- there's no `data.local` persistence layer in this project yet to
 * justify reaching for one just for a 60-second guard.
 */
@Singleton
class PasswordResetCooldownTracker
    @Inject
    constructor() {
        private data class LastSent(val email: String, val sentAtMillis: Long)

        private var lastSent: LastSent? = null

        /** The email the most recent send was for, if any is still within its cooldown window. */
        val lastSentEmail: String?
            get() = lastSent?.email

        fun recordSent(
            email: String,
            nowMillis: Long = System.currentTimeMillis(),
        ) {
            lastSent = LastSent(email, nowMillis)
        }

        /** Seconds left in [cooldownSeconds] since the last send, floored at 0 if none or expired. */
        fun remainingSeconds(
            cooldownSeconds: Int,
            nowMillis: Long = System.currentTimeMillis(),
        ): Int {
            val sentAtMillis = lastSent?.sentAtMillis ?: return 0
            val elapsedSeconds = (nowMillis - sentAtMillis) / 1000
            return (cooldownSeconds - elapsedSeconds).coerceIn(0, cooldownSeconds.toLong()).toInt()
        }
    }
