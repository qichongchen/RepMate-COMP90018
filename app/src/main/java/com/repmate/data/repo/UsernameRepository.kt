package com.repmate.data.repo

/** Outcome of [UsernameRepository.checkAvailability]. */
sealed interface NameAvailability {
    /** Nobody holds this name. */
    data object Available : NameAvailability

    /** The name is already mine (also the case for a case-only change to my own name). */
    data object Mine : NameAvailability

    data object Taken : NameAvailability

    /** Failed the local rules; [reason] is never [NameCheck.VALID]. */
    data class Invalid(val reason: NameCheck) : NameAvailability

    /** Could not find out (offline, timeout, signed out). Never reported as Available. */
    data object Error : NameAvailability
}

/** Outcome of [UsernameRepository.claim]. */
sealed interface ClaimResult {
    data object Success : ClaimResult

    /** Someone else holds the name, including losing a race for it. */
    data object Taken : ClaimResult

    data class Invalid(val reason: NameCheck) : ClaimResult

    /** Nothing was written (offline, token refresh failed, signed out). */
    data object Error : ClaimResult
}

/**
 * Unique display names for real (Google / email) accounts. Guests never use this.
 *
 * Backed by `usernames/{lowercased name}` (the uniqueness guarantee) and `users/{uid}.displayName`
 * (the original-case name); see `firestore.rules` for the enforced model.
 */
interface UsernameRepository {
    /**
     * Is [name] free to claim? Validates locally first, then reads the server (never the cache,
     * which could wrongly say "available").
     */
    suspend fun checkAvailability(name: String): NameAvailability

    /**
     * Claims [name] for the signed-in user, releasing their previous claim in the same atomic
     * transaction (a rename). Fails fast when offline instead of queueing a write.
     */
    suspend fun claim(name: String): ClaimResult

    /**
     * Whether the current user has claimed a name. Answers from the local per-user cache when it
     * can; otherwise one server read with a short timeout, and [ClaimStatus.UNKNOWN] if that
     * cannot finish.
     */
    suspend fun claimStatus(): ClaimStatus

    /**
     * If the current user has claimed a name, makes the Firebase Auth profile name match it, so a
     * provider name (e.g. Google's) never overrides the chosen one on a new device. Best effort.
     */
    suspend fun syncAuthNameIfClaimed()

    /** The provider-given name to derive a prefill from (Auth name, else a linked provider's), or null. */
    fun authNameForSuggestion(): String?

    /** The name the Auth profile currently shows, or null. Used to prefill a rename. */
    fun currentDisplayName(): String?
}
