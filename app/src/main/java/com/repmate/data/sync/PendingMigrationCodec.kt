package com.repmate.data.sync

/**
 * How [DataStorePendingMigrationStore] turns its set of pending merges into one preference string
 * and back, and how it folds in the single marker the store kept before it could hold more than
 * one merge.
 *
 * Pure Kotlin so the awkward parts -- a corrupt record, and the upgrade path that must not drop a
 * merge captured by the previous version of the app -- are covered by a JVM test that runs in CI,
 * rather than only by an instrumented one that does not.
 */
internal object PendingMigrationCodec {
    /** Separates records. Safe: a Firebase UID is letters and digits only, so it holds neither of these. */
    private const val RECORD_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "\t"

    fun encode(entries: List<PendingGuestMigration>): String =
        entries.joinToString(RECORD_SEPARATOR) { "${it.guestUid}$FIELD_SEPARATOR${it.targetUid.orEmpty()}" }

    /** Skips anything malformed rather than throwing: one corrupt record must not block every merge. */
    fun decode(raw: String): List<PendingGuestMigration> =
        raw.split(RECORD_SEPARATOR).mapNotNull { record ->
            val fields = record.split(FIELD_SEPARATOR)
            val guestUid = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PendingGuestMigration(guestUid = guestUid, targetUid = fields.getOrNull(1)?.takeIf { it.isNotBlank() })
        }

    /**
     * [stored] with the pre-upgrade single marker in front of it -- it was captured before
     * anything in the set. Dropped instead if that guest is already in [stored], which is what a
     * half-finished upgrade looks like.
     *
     * Without this, installing a build with the multi-entry store while a merge was pending would
     * orphan that guest's Room rows: exactly the bug the multi-entry store exists to prevent.
     */
    fun foldLegacyMarker(
        stored: List<PendingGuestMigration>,
        legacyGuestUid: String?,
        legacyTargetUid: String?,
    ): List<PendingGuestMigration> {
        if (legacyGuestUid == null || stored.any { it.guestUid == legacyGuestUid }) return stored
        return listOf(PendingGuestMigration(legacyGuestUid, legacyTargetUid)) + stored
    }

    /** [entries] with [migration] replacing the entry for the same guest, in place, or appended. */
    fun upsert(
        entries: List<PendingGuestMigration>,
        migration: PendingGuestMigration,
    ): List<PendingGuestMigration> =
        if (entries.any { it.guestUid == migration.guestUid }) {
            entries.map { if (it.guestUid == migration.guestUid) migration else it }
        } else {
            entries + migration
        }
}
