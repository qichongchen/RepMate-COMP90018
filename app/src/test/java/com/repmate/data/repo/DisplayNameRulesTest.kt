package com.repmate.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNameRulesTest {
    private fun assertValid(name: String) = assertEquals("expected valid: '$name'", NameCheck.VALID, DisplayNameRules.validate(name))

    private fun assertFormatError(name: String) =
        assertEquals("expected format error: '$name'", NameCheck.INVALID_FORMAT, DisplayNameRules.validate(name))

    private fun assertReserved(name: String) = assertEquals("expected reserved: '$name'", NameCheck.RESERVED, DisplayNameRules.validate(name))

    @Test
    fun `letters digits underscores and single inner spaces are valid`() {
        assertValid("abc")
        assertValid("Alex_92")
        assertValid("The Real Alice")
        assertValid("a_b c_d")
    }

    @Test
    fun `length boundaries are 3 and 20`() {
        assertFormatError("ab")
        assertValid("abc")
        assertValid("a".repeat(20))
        assertFormatError("a".repeat(21))
        assertFormatError("")
    }

    @Test
    fun `leading trailing and double spaces are format errors`() {
        assertFormatError(" alice")
        assertFormatError("alice ")
        assertFormatError("al  ice")
        assertFormatError("   ")
    }

    @Test
    fun `characters outside ascii letters digits underscore are format errors`() {
        assertFormatError("al-ice")
        assertFormatError("al.ice")
        assertFormatError("al'ice")
        assertFormatError("José")
        assertFormatError("alice😀")
        assertFormatError("你好世")
        assertFormatError("tab\tname")
        assertFormatError("a/b c")
    }

    @Test
    fun `reserved words are refused whole and case-insensitively`() {
        assertReserved("admin")
        assertReserved("Admin")
        assertReserved("REPMATE")
        assertReserved("Support")
        assertReserved("moderator")
        // Whole word only: containing one is fine.
        assertValid("admin2")
        assertValid("the_admin")
        assertValid("repmate fan")
    }

    @Test
    fun `anything starting with guest is refused`() {
        assertReserved("guest")
        assertReserved("Guest123")
        assertReserved("guest_7")
        assertReserved("GUESTBOOK")
        assertValid("not a guest")
    }

    @Test
    fun `a format error wins over reserved`() {
        assertFormatError("ad")
        assertFormatError("guest!")
    }

    @Test
    fun `id is the lowercased name`() {
        assertEquals("cool_guy 99", DisplayNameRules.idFor("Cool_Guy 99"))
        assertEquals("alice", DisplayNameRules.idFor("ALICE"))
    }

    // ---- suggestion sanitiser ----

    @Test
    fun `suggestion strips accents`() {
        assertEquals("Jose Garcia", DisplayNameRules.suggestFrom("José García"))
        assertEquals("Zoe", DisplayNameRules.suggestFrom("Zoë"))
    }

    @Test
    fun `suggestion turns hyphens and dots into spaces`() {
        assertEquals("Mary Ann", DisplayNameRules.suggestFrom("Mary-Ann"))
        assertEquals("J R Smith", DisplayNameRules.suggestFrom("J.R.Smith"))
    }

    @Test
    fun `suggestion drops other symbols and collapses spaces`() {
        assertEquals("OBrien", DisplayNameRules.suggestFrom("O'Brien"))
        assertEquals("Alex Smith", DisplayNameRules.suggestFrom("  Alex   Smith  "))
        assertEquals("Alex Smith", DisplayNameRules.suggestFrom("Alex 😀 Smith"))
        assertEquals("Alex_92", DisplayNameRules.suggestFrom("Alex_92"))
    }

    @Test
    fun `suggestion shorter than 3 is nothing`() {
        assertNull(DisplayNameRules.suggestFrom("Al"))
        assertNull(DisplayNameRules.suggestFrom("A-"))
        assertNull(DisplayNameRules.suggestFrom("!!!"))
        assertNull(DisplayNameRules.suggestFrom(""))
        assertNull(DisplayNameRules.suggestFrom("   "))
        assertNull(DisplayNameRules.suggestFrom(null))
    }

    @Test
    fun `suggestion is cut to 20 characters and re-trimmed`() {
        assertEquals("a".repeat(20), DisplayNameRules.suggestFrom("a".repeat(30)))
        // The 20th character falls on a space: no trailing space is left behind.
        assertEquals("abcdefghi jklmnopqrs", DisplayNameRules.suggestFrom("abcdefghi jklmnopqrs tuvwxyz"))
        // 49 characters cut to 20 ends on a space; trimming it leaves 19.
        assertEquals("word word word word", DisplayNameRules.suggestFrom("word ".repeat(10)))
    }

    @Test
    fun `non-Latin input gives no suggestion`() {
        assertNull(DisplayNameRules.suggestFrom("张伟"))
        assertNull(DisplayNameRules.suggestFrom("Алексей"))
        assertNull(DisplayNameRules.suggestFrom("محمد"))
    }

    @Test
    fun `mixed Latin and non-Latin keeps only the Latin part when long enough`() {
        assertEquals("Alex", DisplayNameRules.suggestFrom("Alex 张伟"))
        assertNull(DisplayNameRules.suggestFrom("Al 张伟"))
    }

    @Test
    fun `a suggestion can be a reserved word - it is not filtered`() {
        assertEquals("Admin", DisplayNameRules.suggestFrom("Admin"))
    }

    @Test
    fun `every suggestion that is returned is well-formed`() {
        listOf("José García", "Mary-Ann", "O'Brien", "A.B.C.D", "  x y z  ", "abc def ghi jkl mno pqr").forEach { raw ->
            val suggestion = DisplayNameRules.suggestFrom(raw)
            if (suggestion != null) {
                val check = DisplayNameRules.validate(suggestion)
                assert(check != NameCheck.INVALID_FORMAT) { "'$raw' -> '$suggestion' is malformed" }
            }
        }
    }
}
