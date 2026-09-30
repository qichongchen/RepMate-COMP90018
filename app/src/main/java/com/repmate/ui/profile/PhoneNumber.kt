package com.repmate.ui.profile

/** An optional leading `+`, then 7 to 15 digits: the E.164 length range, without insisting on a country. */
private val PHONE_PATTERN = Regex("""^\+?\d{7,15}$""")

/** Characters people type for readability that carry no meaning to the SMS service. */
private val PHONE_SEPARATORS = setOf(' ', '-', '(', ')')

/**
 * Cleans and lightly validates a phone number typed into the emergency contact dialog.
 *
 * Spaces, dashes and parentheses are stripped, then the result must be an optional leading `+`
 * followed by 7-15 digits. The cleaned form is what gets saved, so `SmsManager` receives a normal
 * looking number ("+61412345678", not "+61 412 345 678") and the dialog shows the same thing back
 * when reopened.
 *
 * This is deliberately a sanity check, not real phone-number validation: it cannot know whether a
 * number is reachable or which country it belongs to. It exists to catch the obvious mistakes
 * ("abc", "123") before a safety alert is silently sent nowhere. The "+" is kept because it is
 * what tells the carrier the number already includes a country code.
 *
 * @return the cleaned number, or null if [raw] is not a plausible phone number.
 */
internal fun normalizePhoneNumber(raw: String): String? {
    val cleaned = raw.trim().filterNot { it in PHONE_SEPARATORS }
    return cleaned.takeIf { PHONE_PATTERN.matches(it) }
}
