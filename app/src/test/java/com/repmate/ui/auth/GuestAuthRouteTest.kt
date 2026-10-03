package com.repmate.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [guestAuthRoute] decides, for every sign-in [AuthViewModel] runs, whether a guest is upgraded in
 * place or replaced -- and therefore whether a history merge has to be set up before the call.
 *
 * Getting this wrong is silent and expensive in both directions: treating a link as a replacement
 * leaves a pending merge that can never resolve, and treating a replacement as a link loses the
 * guest's UID before anything records it, which orphans their workouts for good.
 */
class GuestAuthRouteTest {
    @Test
    fun aGuestSigningUpIsLinkedSoTheUidSurvives() {
        // Sign up can always upgrade in place, by email or by Google from the Sign up screen.
        assertEquals(
            GuestAuthRoute.LINK_KEEPING_UID,
            guestAuthRoute(isGuest = true, canLinkToGuest = true),
        )
    }

    @Test
    fun aGuestLoggingInToAnExistingAccountIsReplacedAndMerged() {
        // Firebase replaces the anonymous user here, so the guest's UID has to be captured first.
        assertEquals(
            GuestAuthRoute.REPLACE_AND_MERGE,
            guestAuthRoute(isGuest = true, canLinkToGuest = false),
        )
    }

    @Test
    fun withNoGuestSignedInThereIsNothingToMigrate() {
        assertEquals(GuestAuthRoute.NO_GUEST, guestAuthRoute(isGuest = false, canLinkToGuest = true))
        assertEquals(GuestAuthRoute.NO_GUEST, guestAuthRoute(isGuest = false, canLinkToGuest = false))
    }

    @Test
    fun exactlyOneCaseEverNeedsAMerge() {
        // Spelled out as a table so a later flow added to AuthViewModel has to decide which of the
        // two it is, rather than defaulting into capturing (or not capturing) by accident.
        val routes =
            listOf(true, false).flatMap { isGuest ->
                listOf(true, false).map { canLink ->
                    Triple(isGuest, canLink, guestAuthRoute(isGuest = isGuest, canLinkToGuest = canLink))
                }
            }

        assertEquals(
            listOf(Triple(true, false, GuestAuthRoute.REPLACE_AND_MERGE)),
            routes.filter { it.third == GuestAuthRoute.REPLACE_AND_MERGE },
        )
    }
}
