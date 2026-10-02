package com.repmate.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class SignInCaptionTest {
    @Test
    fun `google provider says Google`() {
        assertEquals("Signed in with Google", signInCaption(listOf("firebase", "google.com")))
    }

    @Test
    fun `password provider says email even when the account has a chosen name`() {
        assertEquals("Signed in with email", signInCaption(listOf("firebase", "password")))
    }

    @Test
    fun `no providers falls back to email`() {
        assertEquals("Signed in with email", signInCaption(emptyList()))
    }
}
