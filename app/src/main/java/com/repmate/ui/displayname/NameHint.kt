package com.repmate.ui.displayname

/** What the availability line under the name field says. Pure state; the screen maps it to text and colour. */
enum class NameHint {
    /** Nothing typed yet. */
    IDLE,
    CHECKING,
    AVAILABLE,

    /** The name is already the user's own (including a case-only change). */
    MINE,
    TAKEN,

    /** Genuine format failure: "Use 3 to 20 letters, numbers or underscores...". */
    INVALID_FORMAT,

    /** Reserved word or a "guest..." name: "That name isn't available". */
    UNAVAILABLE,

    /** Could not check (offline). */
    CANT_CHECK,
}
