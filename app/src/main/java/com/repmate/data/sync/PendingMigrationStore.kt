package com.repmate.data.sync

/**
 * Durable storage for the one pending [PendingGuestMigration], so an unfinished merge survives a
 * crash, a kill or going offline and resumes on the next app launch.
 *
 * An interface so [GuestHistoryMigrator]'s tests can use an in-memory fake instead of DataStore.
 * There is a single slot: starting a new merge replaces an unresolved older one (the older
 * guest's rows are untouched in Room, just no longer scheduled to move).
 */
interface PendingMigrationStore {
    /** The pending merge, or null if there is none. */
    suspend fun get(): PendingGuestMigration?

    /** Replaces whatever is stored with [migration]. */
    suspend fun save(migration: PendingGuestMigration)

    /** Removes the pending merge. Only called once it is finished (or known not to be needed). */
    suspend fun clear()
}
