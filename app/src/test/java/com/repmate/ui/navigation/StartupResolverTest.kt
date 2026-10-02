package com.repmate.ui.navigation

import com.repmate.data.repo.ClaimStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupResolverTest {
    private val neverEmits: Flow<String?> = flow { awaitCancellation() }

    @Test
    fun `no user resolves to welcome`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = flowOf(null),
                    currentUid = { null },
                    hasSeenOnboarding = { error("must not be asked without a user") },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertEquals(RepMateDestinations.WELCOME, result)
        }

    @Test
    fun `user who finished onboarding resolves to home`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = flowOf("uid-1"),
                    currentUid = { "uid-1" },
                    hasSeenOnboarding = { uid -> uid == "uid-1" },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertEquals(RepMateDestinations.HOME, result)
        }

    @Test
    fun `user who has not finished onboarding resolves to onboarding`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = flowOf("uid-1"),
                    currentUid = { "uid-1" },
                    hasSeenOnboarding = { false },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertEquals(RepMateDestinations.ONBOARDING, result)
        }

    @Test
    fun `only the first auth state counts`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = flowOf("uid-1", null),
                    currentUid = { null },
                    hasSeenOnboarding = { true },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertEquals(RepMateDestinations.HOME, result)
        }

    @Test
    fun `auth listener timeout with a current user is treated as signed in`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = neverEmits,
                    currentUid = { "uid-1" },
                    hasSeenOnboarding = { true },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertTrue(result != RepMateDestinations.WELCOME)
            assertEquals(RepMateDestinations.HOME, result)
        }

    @Test
    fun `auth listener timeout with no current user resolves to welcome`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = neverEmits,
                    currentUid = { null },
                    hasSeenOnboarding = { true },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertEquals(RepMateDestinations.WELCOME, result)
        }

    @Test
    fun `failed onboarding read still resolves instead of hanging`() =
        runTest {
            val result =
                resolveStartDestination(
                    firstAuthState = flowOf("uid-1"),
                    currentUid = { "uid-1" },
                    hasSeenOnboarding = { throw java.io.IOException("datastore unavailable") },
                    isAnonymous = { false },
                    claimStatus = { ClaimStatus.CLAIMED },
                )

            assertEquals(RepMateDestinations.ONBOARDING, result)
        }

    @Test
    fun `afterAuth maps onboarding state to home or onboarding`() {
        assertEquals(RepMateDestinations.HOME, RepMateDestinations.afterAuth(hasSeenOnboarding = true))
        assertEquals(RepMateDestinations.ONBOARDING, RepMateDestinations.afterAuth(hasSeenOnboarding = false))
    }

    @Test
    fun `afterAuth sends a user who needs a display name to choose one first`() {
        assertEquals(
            RepMateDestinations.CHOOSE_DISPLAY_NAME,
            RepMateDestinations.afterAuth(hasSeenOnboarding = true, needsDisplayName = true),
        )
        assertEquals(
            RepMateDestinations.CHOOSE_DISPLAY_NAME,
            RepMateDestinations.afterAuth(hasSeenOnboarding = false, needsDisplayName = true),
        )
    }

    private suspend fun resolveWith(
        anonymous: Boolean,
        status: suspend () -> ClaimStatus,
        seen: Boolean = true,
    ) = resolveStartDestination(
        firstAuthState = flowOf("uid-1"),
        currentUid = { "uid-1" },
        hasSeenOnboarding = { seen },
        isAnonymous = { anonymous },
        claimStatus = status,
    )

    @Test
    fun `real user without a claimed name is gated to choose a display name`() =
        runTest {
            assertEquals(
                RepMateDestinations.CHOOSE_DISPLAY_NAME,
                resolveWith(anonymous = false, status = { ClaimStatus.NOT_CLAIMED }),
            )
            // Even if onboarding is not done yet, the name comes first.
            assertEquals(
                RepMateDestinations.CHOOSE_DISPLAY_NAME,
                resolveWith(anonymous = false, status = { ClaimStatus.NOT_CLAIMED }, seen = false),
            )
        }

    @Test
    fun `real user with a claimed name continues to home or onboarding`() =
        runTest {
            assertEquals(RepMateDestinations.HOME, resolveWith(anonymous = false, status = { ClaimStatus.CLAIMED }))
            assertEquals(
                RepMateDestinations.ONBOARDING,
                resolveWith(anonymous = false, status = { ClaimStatus.CLAIMED }, seen = false),
            )
        }

    @Test
    fun `unknown claim status never gates the user`() =
        runTest {
            assertEquals(RepMateDestinations.HOME, resolveWith(anonymous = false, status = { ClaimStatus.UNKNOWN }))
        }

    @Test
    fun `a failed claim check never gates or strands the user`() =
        runTest {
            val result = resolveWith(anonymous = false, status = { throw java.io.IOException("offline") })

            assertEquals(RepMateDestinations.HOME, result)
        }

    @Test
    fun `guests skip the display name gate and are never asked`() =
        runTest {
            val result = resolveWith(anonymous = true, status = { error("must not be asked for a guest") })

            assertEquals(RepMateDestinations.HOME, result)
        }
}
