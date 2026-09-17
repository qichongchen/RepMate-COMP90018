package com.repmate.ui.auth

import com.google.firebase.auth.FirebaseAuth

/**
 * How the signed-in Firebase user should be shown across the app. Home's top-bar avatar and
 * Profile's header both derive from [accountDisplayFor] rather than each re-deriving "Google
 * name, then email, then guest" slightly differently -- one function, two callers.
 */
data class AccountDisplay(
    val name: String,
    /** Null means "show a generic person-silhouette icon instead of a letter" -- a guest has neither a name nor an email to derive one from. */
    val avatarInitial: String?,
    val caption: String,
)

/**
 * Google sign-ups have a `displayName`; email/password sign-ups don't (that flow deliberately
 * doesn't collect a name -- see `SignUpScreen`'s NOTE on that), so this falls back to the email
 * instead. A guest (`signInAnonymously()`) has neither, so it falls back to a generic "Guest"
 * label and a null avatar initial rather than a fake letter for an account with no name or email.
 */
fun accountDisplayFor(firebaseAuth: FirebaseAuth): AccountDisplay {
    val user = firebaseAuth.currentUser

    val displayName = user?.displayName
    if (!displayName.isNullOrBlank()) {
        return AccountDisplay(
            name = displayName,
            avatarInitial = displayName.first().uppercase(),
            caption = "Signed in with Google",
        )
    }

    val email = user?.email
    if (!email.isNullOrBlank()) {
        return AccountDisplay(
            name = email,
            avatarInitial = email.first().uppercase(),
            caption = "Signed in with email",
        )
    }

    // Covers the explicit isAnonymous == true case and, degrading gracefully (Golden Rule 7),
    // the edge case of no signed-in user at all -- neither has a name or email to derive an
    // initial from, so both get the same guest-shaped fallback rather than a crash.
    return AccountDisplay(
        name = "Guest",
        avatarInitial = null,
        caption = "Guest session",
    )
}
