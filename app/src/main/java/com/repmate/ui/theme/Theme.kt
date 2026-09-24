package com.repmate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val RepMateDarkColorScheme = darkColorScheme(
    primary = LimeAccent,
    onPrimary = OnLimeAccent,
    background = CharcoalBackground,
    onBackground = Color.White,
    surface = CharcoalSurface,
    onSurface = Color.White,
    onSurfaceVariant = MutedGrayDark,
    error = ErrorDark,
    onError = OnErrorDark
)

private val RepMateLightColorScheme = lightColorScheme(
    primary = LimeAccent,
    onPrimary = OnLimeAccent,
    background = LightBackground,
    onBackground = DarkTextOnLight,
    surface = LightSurface,
    onSurface = DarkTextOnLight,
    onSurfaceVariant = MutedGrayLight,
    error = ErrorLight,
    onError = OnErrorLight
)

/**
 * Whether the enclosing [RepMateTheme] is dark, for the rare composable that needs to pick a
 * light/dark *resource* (e.g. `ExerciseTutorialDialog`'s illustrations) rather than a color.
 *
 * Needed because the app's dark mode is a plain Compose flag (Profile's toggle ->
 * [ThemePreferences] -> `MainActivity` -> [RepMateTheme]), not the system night-mode
 * configuration: `drawable-night/` and `isSystemInDarkTheme()` follow the phone's setting, which
 * can disagree with the in-app toggle. Static because it only changes when the whole theme does.
 */
val LocalRepMateDarkTheme = staticCompositionLocalOf { true }

/**
 * RepMate's Material 3 theme: charcoal-and-lime dark mode by default, with a full light
 * variant available for the rare screen/user that wants it.
 *
 * NOTE: [darkTheme] defaults to `true` rather than [androidx.compose.foundation.isSystemInDarkTheme],
 * because the fitness-tracker look this app is built around -- the charcoal background,
 * white stat numbers, lime highlights -- is the intended look for most screens, not just a
 * fallback for users with dark mode on. Dynamic (wallpaper-based) color is intentionally not
 * wired in either: it would replace the lime accent with a device-specific color and break
 * the brand look the palette above is designed to give.
 */
@Composable
fun RepMateTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) RepMateDarkColorScheme else RepMateLightColorScheme

    CompositionLocalProvider(LocalRepMateDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = RepMateShapes,
            content = content
        )
    }
}
