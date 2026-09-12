package com.repmate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RepMate's brand palette.
 *
 * NOTE: kept to exactly the roles [androidx.compose.material3.ColorScheme] needs
 * (primary/onPrimary, background/onBackground, surface/onSurface, onSurfaceVariant for
 * secondary text, error/onError) -- no extra custom colors, since none of the current
 * designs need a role Material 3 doesn't already have. Add one later only if a design
 * clearly can't be expressed with these (e.g. a "success" green distinct from the accent).
 */

/** Signature acid/lime accent: primary actions, progress indicators, highlighted stats. Same value in both themes. */
val LimeAccent = Color(0xFFC6F135)

/** Text/icons on top of [LimeAccent] -- the accent is bright enough that dark text reads better than white. */
val OnLimeAccent = Color(0xFF16180F)

// --- Dark theme (the default look) ---

/** Near-black charcoal, not pure black -- pure black crushes contrast on OLED and looks harsh next to the lime accent. */
val CharcoalBackground = Color(0xFF121212)

/** A touch lighter than [CharcoalBackground] so cards and surfaces separate from the page behind them. */
val CharcoalSurface = Color(0xFF1C1C1C)

/** Muted gray for secondary/supporting text in dark mode -- full white is reserved for primary text and stat numbers. */
val MutedGrayDark = Color(0xFFA0A0A0)

/** Material 3 baseline dark error pair. */
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)

// --- Light theme ---

val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)

/** Dark text for the light theme's onBackground/onSurface. */
val DarkTextOnLight = Color(0xFF1A1A1A)

/** Muted gray for secondary/supporting text in light mode. */
val MutedGrayLight = Color(0xFF5F5F5F)

/** Material 3 baseline light error pair. */
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
