package com.example.repmate.data.auth

interface AuthRepository {
    suspend fun signInAnonymously(): Result<String>
    fun getCurrentUserId(): String?
}