package com.repmate.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.guestMigrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "guest_migration_prefs")

/** Every pending merge, one record per line: `guestUid \t targetUid`, the target empty if unknown. */
private val PENDING_KEY = stringPreferencesKey("pending_migrations")

// The single-marker keys this store used before it could hold more than one merge. Still read, so
// an app update landing mid-merge does not drop it; cleared on the first write after that.
private val LEGACY_GUEST_UID_KEY = stringPreferencesKey("guest_uid")
private val LEGACY_TARGET_UID_KEY = stringPreferencesKey("target_uid")

/**
 * [PendingMigrationStore] backed by Preferences DataStore, the same on-device mechanism as
 * `OnboardingPreferences`: this is local bookkeeping that has no reason to sync or survive a
 * reinstall (a reinstall also wipes the Room rows it refers to).
 *
 * Every write is one `edit {}`, so what is stored is always a whole set, never half of one. The
 * encoding and the legacy fold live in [PendingMigrationCodec], where a JVM test can reach them.
 */
@Singleton
class DataStorePendingMigrationStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PendingMigrationStore {
        override suspend fun all(): List<PendingGuestMigration> = readAll(context.guestMigrationDataStore.data.first())

        override suspend fun save(migration: PendingGuestMigration) {
            context.guestMigrationDataStore.edit { prefs ->
                write(prefs, PendingMigrationCodec.upsert(readAll(prefs), migration))
            }
        }

        override suspend fun remove(guestUid: String) {
            context.guestMigrationDataStore.edit { prefs ->
                write(prefs, readAll(prefs).filterNot { it.guestUid == guestUid })
            }
        }

        private fun readAll(prefs: Preferences): List<PendingGuestMigration> =
            PendingMigrationCodec.foldLegacyMarker(
                stored = prefs[PENDING_KEY]?.let(PendingMigrationCodec::decode).orEmpty(),
                legacyGuestUid = prefs[LEGACY_GUEST_UID_KEY],
                legacyTargetUid = prefs[LEGACY_TARGET_UID_KEY],
            )

        private fun write(
            prefs: MutablePreferences,
            entries: List<PendingGuestMigration>,
        ) {
            // readAll has already folded the legacy marker into `entries`, so clear it here --
            // leaving it behind would resurrect a merge that has just finished.
            prefs.remove(LEGACY_GUEST_UID_KEY)
            prefs.remove(LEGACY_TARGET_UID_KEY)
            if (entries.isEmpty()) prefs.remove(PENDING_KEY) else prefs[PENDING_KEY] = PendingMigrationCodec.encode(entries)
        }
    }
