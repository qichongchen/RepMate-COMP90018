package com.repmate.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberTest {
    @Test
    fun `valid international number keeps its plus and loses its spaces`() {
        assertEquals("+61412345678", normalizePhoneNumber("+61 412 345 678"))
    }

    @Test
    fun `valid local number is accepted`() {
        assertEquals("0412345678", normalizePhoneNumber("0412 345 678"))
    }

    @Test
    fun `dashes and parentheses are stripped`() {
        assertEquals("0391234567", normalizePhoneNumber("(03) 9123-4567"))
        assertEquals("+14155552671", normalizePhoneNumber("+1 (415) 555-2671"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("+61412345678", normalizePhoneNumber("  +61412345678 "))
    }

    @Test
    fun `too short is rejected`() {
        assertNull(normalizePhoneNumber("123"))
        assertNull(normalizePhoneNumber("123456"))
    }

    @Test
    fun `seven and fifteen digits are the accepted bounds`() {
        assertEquals("1234567", normalizePhoneNumber("1234567"))
        assertEquals("+123456789012345", normalizePhoneNumber("+123456789012345"))
        assertNull(normalizePhoneNumber("+1234567890123456"))
    }

    @Test
    fun `letters are rejected`() {
        assertNull(normalizePhoneNumber("abc"))
        assertNull(normalizePhoneNumber("+61 412 abc 678"))
        assertNull(normalizePhoneNumber("0412345678x"))
    }

    @Test
    fun `plus is only allowed at the start`() {
        assertNull(normalizePhoneNumber("61+412345678"))
        assertNull(normalizePhoneNumber("++61412345678"))
        assertNull(normalizePhoneNumber("+"))
    }

    @Test
    fun `empty and blank are rejected`() {
        assertNull(normalizePhoneNumber(""))
        assertNull(normalizePhoneNumber("   "))
    }
}
