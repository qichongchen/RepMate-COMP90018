package com.repmate.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.guestMigrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "guest_migration_prefs")

private val GUEST_UID_KEY = stringPreferencesKey("guest_uid")
private val TARGET_UID_KEY = stringPreferencesKey("target_uid")

/**
 * [PendingMigrationStore] backed by Preferences DataStore, the same on-device mechanism as
 * `OnboardingPreferences`: this is local bookkeeping that has no reason to sync or survive a
 * reinstall (a reinstall also wipes the Room rows it refers to).
 */
@Singleton
class DataStorePendingMigrationStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PendingMigrationStore {
        override suspend fun get(): PendingGuestMigration? {
            val prefs = context.guestMigrationDataStore.data.first()
            val guestUid = prefs[GUEST_UID_KEY] ?: return null
            return PendingGuestMigration(guestUid = guestUid, targetUid = prefs[TARGET_UID_KEY])
        }

        override suspend fun save(migration: PendingGuestMigration) {
            // One edit{} so guest and target are written atomically, never half of a marker.
            context.guestMigrationDataStore.edit { prefs ->
                prefs[GUEST_UID_KEY] = migration.guestUid
                val target = migration.targetUid
                if (target != null) prefs[TARGET_UID_KEY] = target else prefs.remove(TARGET_UID_KEY)
            }
        }

        override suspend fun clear() {
            context.guestMigrationDataStore.edit { it.clear() }
        }
    }
