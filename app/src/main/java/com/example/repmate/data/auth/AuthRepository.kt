package com.example.repmate.data.auth

interface AuthRepository {
    suspend fun signInAnonymously(): Result<String>
    fun getCurrentUserId(): String?

    /**
     * Whether the signed-in user is an anonymous guest rather than a real account.
     *
     * NOTE: defaults to `true` (fail closed) so an implementation that does not know never makes
     * the app treat someone as a real account, e.g. by publishing a Ghost Duel score for them.
     * [FirebaseAuthRepository] overrides it with the real answer.
     */
    fun isCurrentUserAnonymous(): Boolean = true
}
