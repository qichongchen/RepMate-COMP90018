package com.repmate.di

import android.content.Context
import android.hardware.SensorManager
import com.repmate.sensors.DeviceSensorSource
import com.repmate.sensors.SensorSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the real [SensorSource] the app runs on, following the same
 * [SingletonComponent]/`@Singleton` pattern [RepositoryModule] already uses.
 *
 * Singleton-scoped is safe specifically because [DeviceSensorSource]'s own doc assumes one
 * collector at a time, and this app only ever has one Live Workout screen visible at once -- a
 * new visit's collection only starts once the previous one's has fully torn down via
 * `awaitClose`, so the single shared instance is never collected from concurrently.
 */
@Module
@InstallIn(SingletonComponent::class)
object SensorModule {
    @Provides
    fun provideSensorManager(
        @ApplicationContext context: Context,
    ): SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Provides
    @Singleton
    fun provideSensorSource(sensorManager: SensorManager): SensorSource = DeviceSensorSource(sensorManager)
}
