package com.repmate.ui.auth

import android.util.Patterns

/**
 * Basic email-format validation shared by [AuthViewModel] and `ForgotPasswordViewModel` -- both
 * need the exact same check, so it lives here once rather than as two copies that could drift.
 */
internal fun validateEmailFormat(email: String): String? =
    when {
        email.isBlank() -> "Enter your email."
        !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Enter a valid email address."
        else -> null
    }
