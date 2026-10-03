package com.repmate.data.sync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * The two pieces of [GuestMigrationLauncher] that can be tested without Firebase: the wait for a
 * restored session at app start, and the rule that nothing the merge does may break sign-in.
 *
 * The launcher itself takes `FirebaseAuth` and a process-wide scope, neither of which exists in a
 * JVM test, so the logic lives in top-level functions beside it -- the same split
 * `resolveStartDestination` uses for `StartupViewModel`.
 */
class GuestMigrationLauncherTest {
    @Test
    fun takesTheFirstSignedInUid() =
        runTest {
            assertEquals("account-uid", awaitSignedInUid(flowOf("account-uid"), timeoutMs = 1_000))
        }

    @Test
    fun waitsPastTheSignedOutStateFirebaseReportsFirst() =
        runTest {
            // The cold-start shape: Firebase says "nobody" before it has read the stored session.
            val updates =
                flow {
                    emit(null)
                    delay(200)
                    emit("account-uid")
                }

            assertEquals("account-uid", awaitSignedInUid(updates, timeoutMs = 1_000))
        }

    @Test
    fun givesUpWhenFirebaseNeverReportsAUser() =
        runTest {
            // No connectivity and a slow init: the resume is skipped rather than holding a
            // coroutine open for the life of the process. The next launch tries again.
            val neverSignsIn =
                flow {
                    emit(null)
                    awaitCancellation()
                }

            assertNull(awaitSignedInUid(neverSignsIn, timeoutMs = 1_000))
        }

    @Test
    fun aFailedMergeStepIsReportedButNotThrown() =
        runTest {
            val reported = mutableListOf<Throwable>()

            // No assertFailsWith here: the point is that nothing comes out, because the caller is
            // in the middle of a sign-in that must still succeed.
            swallowingFailures(onError = { reported += it }) { throw IllegalStateException("datastore unavailable") }

            assertEquals(1, reported.size)
            assertEquals("datastore unavailable", reported.single().message)
        }

    @Test
    fun cancellationIsNotTreatedAsAFailure() =
        runTest {
            val reported = mutableListOf<Throwable>()

            assertFailsWith<kotlinx.coroutines.CancellationException> {
                swallowingFailures(onError = { reported += it }) {
                    throw kotlinx.coroutines.CancellationException("scope closed")
                }
            }

            // Swallowing this one would leave a cancelled scope thinking it finished its work.
            assertEquals(emptyList<Throwable>(), reported)
        }
}
