package com.repmate.di

import com.repmate.data.local.RoomSessionRepository
import com.repmate.data.local.StubCalibrationRepository
import com.repmate.data.repo.CalibrationRepository
import com.repmate.data.repo.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        implementation: RoomSessionRepository
    ): SessionRepository

    @Binds
    @Singleton
    abstract fun bindCalibrationRepository(
        implementation: StubCalibrationRepository
    ): CalibrationRepository
}