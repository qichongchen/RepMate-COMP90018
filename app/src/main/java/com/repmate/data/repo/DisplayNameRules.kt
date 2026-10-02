package com.repmate.data.repo

import java.text.Normalizer
import java.util.Locale

/** Why a display name was (or was not) accepted by [DisplayNameRules.validate]. */
enum class NameCheck {
    VALID,

    /** Wrong length or characters, or leading/trailing/double spaces. */
    INVALID_FORMAT,

    /** Well-formed but not allowed: a reserved word (admin, ...) or anything starting with "guest". */
    RESERVED,
}

/**
 * The display-name rules, mirrored from the deployed `firestore.rules` so the app can tell the
 * user "no" instantly instead of waiting for a permission-denied.
 *
 * Keep this in step with `validDisplayName` / `validUsernameId` in `firestore.rules`: the rules
 * are the authority (they are what actually stops a bad write), this is the same check run
 * earlier. Pure Kotlin, no Android, so the UI and the tests share one implementation.
 *
 * A name is valid when it is 3 to 20 characters of ASCII letters, digits and underscores, with
 * single spaces allowed only between words. Its username id is the lowercased name (see [idFor]).
 */
object DisplayNameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20

    private val FORMAT = Regex("[A-Za-z0-9_]+( [A-Za-z0-9_]+)*")

    // NOTE: reserved words are matched whole ("admin" is refused, "admin2" is not), like the rules.
    private val RESERVED_WORDS = setOf("admin", "repmate", "support", "moderator")
    private const val RESERVED_PREFIX = "guest"

    /** The `usernames/{id}` document id for [name]: the name lowercased. Only meaningful for valid names. */
    fun idFor(name: String): String = name.lowercase(Locale.ROOT)

    /** Checks [name] exactly as typed (no trimming: a trailing space is a format error, as in the rules). */
    fun validate(name: String): NameCheck {
        if (name.length !in MIN_LENGTH..MAX_LENGTH || !FORMAT.matches(name)) return NameCheck.INVALID_FORMAT
        val id = idFor(name)
        if (id in RESERVED_WORDS || id.startsWith(RESERVED_PREFIX)) return NameCheck.RESERVED
        return NameCheck.VALID
    }

    /**
     * Turns the name a sign-in provider gave us (e.g. a Google account name) into something that
     * can prefill the "choose a name" field, or null if nothing sensible is left.
     *
     * Steps, in order: strip accents (NFD splits "é" into "e" + a combining mark, then the marks
     * go), turn hyphens and dots into spaces (so "Mary-Ann" becomes two words), drop everything
     * else outside the allowed set, collapse runs of spaces, trim, and cut to [MAX_LENGTH]. Fewer
     * than [MIN_LENGTH] characters left means no suggestion. Non-Latin scripts therefore yield
     * nothing rather than a mangled guess.
     *
     * The result is a *suggestion*: it is not checked for reserved words or availability.
     */
    fun suggestFrom(authName: String?): String? {
        if (authName.isNullOrBlank()) return null
        val withoutAccents =
            Normalizer
                .normalize(authName, Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val cleaned =
            withoutAccents
                .map { if (it == '-' || it == '.') ' ' else it }
                .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == ' ' }
                .joinToString("")
                .replace(Regex(" +"), " ")
                .trim()
        // Trim again after the cut: the 20th character may have been the space before a dropped word.
        return cleaned.take(MAX_LENGTH).trim().takeIf { it.length >= MIN_LENGTH }
    }
}
