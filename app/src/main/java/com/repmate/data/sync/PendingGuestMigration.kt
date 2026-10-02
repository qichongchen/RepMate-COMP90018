package com.repmate.data.sync

/**
 * A guest-to-account history merge that has been started but not yet finished.
 *
 * @property guestUid the anonymous user whose Room sessions are to be merged. Captured *before*
 *   the sign-in call, because a successful sign-in replaces the guest and the UID would be lost.
 * @property targetUid the account that should receive them. Null until the sign-in has actually
 *   succeeded (nothing to merge into yet); once set, a resume only proceeds while that same
 *   account is the signed-in user, so the guest's workouts can never land in an account the
 *   user did not log in to.
 */
data class PendingGuestMigration(
    val guestUid: String,
    val targetUid: String? = null,
)
