package com.example.repmate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * [Configuration.Provider] switches WorkManager to on-demand initialization (see the
 * `androidx.startup.InitializationProvider` override in the manifest) so it can be handed
 * [workerFactory] instead of its own default one -- that's what lets
 * `com.repmate.safety.CheckInNotifyWorker`/`CheckInEscalateWorker` take real dependencies via
 * `@AssistedInject` rather than a manual service locator.
 */
@HiltAndroidApp
class RepMateApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}

