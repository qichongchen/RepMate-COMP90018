package com.repmate.di

import android.content.Context
import androidx.work.WorkManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the framework singletons safety check-in (`com.repmate.safety`) needs, following the
 * same [SingletonComponent]/`@Provides` pattern as [SensorModule]. Interface-to-implementation
 * bindings for that package live in [SafetyBindingsModule] instead, matching [RepositoryModule]'s
 * split between the two module styles.
 */
@Module
@InstallIn(SingletonComponent::class)
object SafetyModule {
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context,
    ): FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
}
