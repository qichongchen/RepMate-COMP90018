package com.repmate.di

import com.repmate.safety.CheckInAckStore
import com.repmate.safety.CheckInWorkGateway
import com.repmate.safety.DataStoreCheckInAckStore
import com.repmate.safety.FusedLastLocationProvider
import com.repmate.safety.LastLocationProvider
import com.repmate.safety.SafetyAlertSender
import com.repmate.safety.SmsSafetyAlertSender
import com.repmate.safety.WorkManagerCheckInGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Interface-to-implementation bindings for `com.repmate.safety`, split from [SafetyModule]'s
 * `@Provides` methods the same way [RepositoryModule] is split from [SensorModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SafetyBindingsModule {
    @Binds
    @Singleton
    abstract fun bindLastLocationProvider(implementation: FusedLastLocationProvider): LastLocationProvider

    @Binds
    @Singleton
    abstract fun bindSafetyAlertSender(implementation: SmsSafetyAlertSender): SafetyAlertSender

    @Binds
    @Singleton
    abstract fun bindCheckInWorkGateway(implementation: WorkManagerCheckInGateway): CheckInWorkGateway

    @Binds
    @Singleton
    abstract fun bindCheckInAckStore(implementation: DataStoreCheckInAckStore): CheckInAckStore
}
