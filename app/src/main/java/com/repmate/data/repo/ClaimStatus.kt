package com.repmate.data.repo

/**
 * Whether the signed-in user has claimed a unique display name.
 *
 * [UNKNOWN] is the honest third answer for "we could not find out" (offline, timeout): callers
 * must not treat it as "not claimed", or an offline user would be trapped on the choose-name
 * screen. See [needsDisplayNameGate].
 */
enum class ClaimStatus { CLAIMED, NOT_CLAIMED, UNKNOWN }

/**
 * The gate decision in one place: only a real (non-anonymous) user who is *known* not to have a
 * name is sent to choose one. Guests never claim a name, and an unknown status lets the user
 * through; the check runs again next launch.
 */
fun needsDisplayNameGate(
    isAnonymous: Boolean,
    status: ClaimStatus,
): Boolean = !isAnonymous && status == ClaimStatus.NOT_CLAIMED
