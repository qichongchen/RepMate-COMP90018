package com.example.repmate.di

import com.example.repmate.data.auth.AuthRepository
import com.example.repmate.data.auth.FirebaseAuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        firebaseAuthRepository: FirebaseAuthRepository
    ): AuthRepository
}