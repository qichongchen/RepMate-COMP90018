package com.repmate.data.sync

/**
 * Durable storage for the guest merges that have not finished yet, so an unfinished merge survives
 * a crash, a kill or going offline and resumes on the next app launch.
 *
 * An interface so [GuestHistoryMigrator]'s tests can use an in-memory fake instead of DataStore.
 *
 * ## Why this holds a set, not one slot
 * It used to hold exactly one marker, and [save] replaced whatever was there. A second guest
 * session -- sign out, use the app as a guest again, log in again -- overwrote the first marker
 * while that first guest's rows were still in Room under a UID nobody holds any more. Nothing
 * could find them again: they were orphaned for good.
 *
 * So every pending merge is kept, keyed by [PendingGuestMigration.guestUid], until it finishes or
 * is explicitly discarded. [save] upserts by that key, which is what lets a target be recorded on
 * an existing entry without adding a second entry for the same guest.
 */
interface PendingMigrationStore {
    /** Every merge still pending, oldest capture first. Empty when there is nothing to do. */
    suspend fun all(): List<PendingGuestMigration>

    /**
     * Adds [migration], or replaces the entry with the same [PendingGuestMigration.guestUid],
     * keeping its position. Entries for other guests are left alone.
     */
    suspend fun save(migration: PendingGuestMigration)

    /** Removes the entry for [guestUid], if any. A guest with no entry is not an error. */
    suspend fun remove(guestUid: String)
}
