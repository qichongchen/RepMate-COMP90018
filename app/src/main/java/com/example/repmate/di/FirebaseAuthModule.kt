package com.example.repmate.di

import com.google.firebase.auth.FirebaseAuth
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.google.firebase.firestore.FirebaseFirestore

/** Provides the app's single [FirebaseAuth] instance, so ViewModels can take it as a constructor dependency instead of calling `FirebaseAuth.getInstance()` directly. */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseAuthModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore =
        FirebaseFirestore.getInstance()
}
