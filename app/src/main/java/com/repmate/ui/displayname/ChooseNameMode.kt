package com.repmate.ui.displayname

/** How the choose-name screen was reached; decides the back button and where "done" goes. */
enum class ChooseNameMode {
    /**
     * Right after sign-in for a real account with no claimed name. The root of the back stack:
     * no on-screen back button, and system back exits the app (the next launch gates again).
     */
    GATE,

    /** A guest who just upgraded from Profile. Back returns to Profile; done also returns there. */
    UPGRADE,

    /** Changing an existing name from Profile. Back returns to Profile; done also returns there. */
    RENAME,
    ;

    /** Only the gate hides the on-screen back button. */
    val showsBackButton: Boolean get() = this != GATE

    companion object {
        /** Route argument -> mode; anything unrecognised degrades to the strictest one, [GATE]. */
        fun fromArgument(raw: String?): ChooseNameMode = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: GATE
    }
}
