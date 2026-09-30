package com.repmate.ui.navigation

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
                )

            assertEquals(RepMateDestinations.ONBOARDING, result)
        }

    @Test
    fun `afterAuth maps onboarding state to home or onboarding`() {
        assertEquals(RepMateDestinations.HOME, RepMateDestinations.afterAuth(hasSeenOnboarding = true))
        assertEquals(RepMateDestinations.ONBOARDING, RepMateDestinations.afterAuth(hasSeenOnboarding = false))
    }
}
