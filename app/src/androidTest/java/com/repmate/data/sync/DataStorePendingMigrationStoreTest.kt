package com.repmate.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DataStorePendingMigrationStore] against real DataStore. That a pending merge is still there
 * after the process which wrote it is gone is the whole reason the marker exists, and only the
 * real implementation can show it.
 *
 * The encoding and the legacy-marker fold are covered on the JVM by `PendingMigrationCodecTest`.
 */
@RunWith(AndroidJUnit4::class)
class DataStorePendingMigrationStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val store = DataStorePendingMigrationStore(context)

    // A second instance over the same file, standing in for the next launch of the app.
    private val afterRestart = DataStorePendingMigrationStore(context)

    @Before
    fun clearStore() =
        runTest {
            store.all().forEach { store.remove(it.guestUid) }
            assertEquals(emptyList<PendingGuestMigration>(), store.all())
        }

    @Test
    fun aPendingMergeIsStillThereOnTheNextLaunch() =
        runTest {
            store.save(PendingGuestMigration(guestUid = "guest-1"))
            store.save(PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"))

            assertEquals(
                listOf(PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1")),
                afterRestart.all(),
            )
        }

    @Test
    fun twoGuestsAreBothKept() =
        runTest {
            store.save(PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"))
            store.save(PendingGuestMigration(guestUid = "guest-2"))

            assertEquals(
                listOf(
                    PendingGuestMigration(guestUid = "guest-1", targetUid = "account-1"),
                    PendingGuestMigration(guestUid = "guest-2"),
                ),
                afterRestart.all(),
            )
        }

    @Test
    fun removingOneLeavesTheOther() =
        runTest {
            store.save(PendingGuestMigration(guestUid = "guest-1"))
            store.save(PendingGuestMigration(guestUid = "guest-2"))

            store.remove("guest-1")

            assertEquals(listOf(PendingGuestMigration(guestUid = "guest-2")), afterRestart.all())
        }

    @Test
    fun removingAGuestWithNoEntryIsNotAnError() =
        runTest {
            store.remove("never-seen-this-uid")

            assertEquals(emptyList<PendingGuestMigration>(), store.all())
        }
}
