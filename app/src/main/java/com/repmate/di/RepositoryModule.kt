package com.repmate.di

import com.repmate.data.local.RoomSessionRepository
import com.repmate.data.local.RoomCalibrationRepository
import com.repmate.data.repo.CalibrationRepository
import com.repmate.data.repo.SessionRepository
import com.repmate.data.sync.SyncingSessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.repmate.data.cloud.FirestoreLeaderboardRepository
import com.repmate.data.repo.LeaderboardRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        implementation: SyncingSessionRepository
    ): SessionRepository

    @Binds
    @Singleton
    abstract fun bindCalibrationRepository(
        implementation: RoomCalibrationRepository
    ): CalibrationRepository

    @Binds
    @Singleton
    abstract fun bindLeaderboardRepository(
        implementation: FirestoreLeaderboardRepository
    ): LeaderboardRepository
}