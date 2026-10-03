package com.repmate.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PendingMigrationCodec]: how the pending merges survive a process death, and -- the part worth
 * testing hardest -- how the single marker written by the previous version of the app is carried
 * over instead of dropped.
 *
 * Dropping it would orphan that guest's Room rows for good, which is the whole reason the store
 * holds a set now. The store itself needs DataStore and a Context; this does not.
 */
class PendingMigrationCodecTest {
    @Test
    fun aSetSurvivesAnEncodeAndDecode() {
        val entries =
            listOf(
                PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"),
                // Captured but not yet resolved: the sign-in had not come back.
                PendingGuestMigration(guestUid = "guest-2", targetUid = null),
            )

        assertEquals(entries, PendingMigrationCodec.decode(PendingMigrationCodec.encode(entries)))
    }

    @Test
    fun anEmptySetEncodesToNothing() {
        assertEquals("", PendingMigrationCodec.encode(emptyList()))
        assertEquals(emptyList<PendingGuestMigration>(), PendingMigrationCodec.decode(""))
    }

    @Test
    fun aCorruptRecordIsSkippedAndTheRestStillLoad() {
        // One unreadable line must not take the other guest's merge down with it.
        val raw = "\n" + "\t\t" + "\n" + "guest-2\taccount-1"

        assertEquals(
            listOf(PendingGuestMigration(guestUid = "guest-2", targetUid = "account-1")),
            PendingMigrationCodec.decode(raw),
        )
    }

    @Test
    fun theOldSingleMarkerIsCarriedOverOnUpgrade() {
        // The app updated while a merge was pending: the old keys are all there is.
        assertEquals(
            listOf(PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1")),
            PendingMigrationCodec.foldLegacyMarker(
                stored = emptyList(),
                legacyGuestUid = "guest-1",
                legacyTargetUid = "account-1",
            ),
        )
    }

    @Test
    fun theOldMarkerGoesInFrontOfTheNewerCaptures() {
        // It was captured before anything in the set, and resume walks the queue in order.
        assertEquals(
            listOf(
                PendingGuestMigration(guestUid = "guest-1", targetUid = null),
                PendingGuestMigration(guestUid = "guest-2", targetUid = "account-1"),
            ),
            PendingMigrationCodec.foldLegacyMarker(
                stored = listOf(PendingGuestMigration(guestUid = "guest-2", targetUid = "account-1")),
                legacyGuestUid = "guest-1",
                legacyTargetUid = null,
            ),
        )
    }

    @Test
    fun theOldMarkerIsIgnoredOnceItsGuestIsInTheSet() {
        // What a half-finished upgrade looks like: already folded in and written, legacy keys not
        // yet cleared. Folding again would merge the same guest twice.
        val stored = listOf(PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"))

        assertEquals(
            stored,
            PendingMigrationCodec.foldLegacyMarker(
                stored = stored,
                legacyGuestUid = "guest-1",
                legacyTargetUid = "account-1",
            ),
        )
    }

    @Test
    fun noOldMarkerLeavesTheSetAlone() {
        val stored = listOf(PendingGuestMigration(guestUid = "guest-1", targetUid = null))

        assertEquals(stored, PendingMigrationCodec.foldLegacyMarker(stored, null, null))
    }

    @Test
    fun upsertReplacesInPlaceSoTheQueueKeepsItsOrder() {
        val entries =
            listOf(
                PendingGuestMigration(guestUid = "guest-1", targetUid = null),
                PendingGuestMigration(guestUid = "guest-2", targetUid = null),
            )

        val withTarget = PendingMigrationCodec.upsert(entries, PendingGuestMigration("guest-1", "account-1"))

        assertEquals(
            listOf(
                PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"),
                PendingGuestMigration(guestUid = "guest-2", targetUid = null),
            ),
            withTarget,
        )
    }

    @Test
    fun upsertAppendsAGuestItHasNotSeen() {
        val entries = listOf(PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"))

        assertEquals(
            entries + PendingGuestMigration(guestUid = "guest-2", targetUid = null),
            PendingMigrationCodec.upsert(entries, PendingGuestMigration("guest-2")),
        )
    }
}
