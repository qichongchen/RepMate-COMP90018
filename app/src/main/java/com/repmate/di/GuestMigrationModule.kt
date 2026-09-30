package com.repmate.di

import com.repmate.data.cloud.FirestoreMigrationUploader
import com.repmate.data.local.RoomGuestSessionStore
import com.repmate.data.sync.DataStorePendingMigrationStore
import com.repmate.data.sync.GuestSessionStore
import com.repmate.data.sync.MigrationUploader
import com.repmate.data.sync.PendingMigrationStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Bindings for merging a guest's workout history into an account (see `GuestHistoryMigrator`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class GuestMigrationModule {
    @Binds
    @Singleton
    abstract fun bindPendingMigrationStore(implementation: DataStorePendingMigrationStore): PendingMigrationStore

    @Binds
    @Singleton
    abstract fun bindGuestSessionStore(implementation: RoomGuestSessionStore): GuestSessionStore

    @Binds
    @Singleton
    abstract fun bindMigrationUploader(implementation: FirestoreMigrationUploader): MigrationUploader

    companion object {
        /** SupervisorJob so one failed merge can't cancel unrelated work sharing the scope. */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
