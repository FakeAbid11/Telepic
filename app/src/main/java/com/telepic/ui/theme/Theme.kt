package com.telepic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended Telepic color tokens that sit on top of the Material 3 [ColorScheme].
 * These carry the product's media-first identity (deep backdrop behind photo grids,
 * scrims, etc.) so screens never hard-code raw colors.
 */
@Immutable
data class TelepicExtendedColors(
    /** Deep, near-black backdrop that makes media pop. */
    val mediaBackdrop: Color,
    /** Scrim used behind overlays on media. */
    val scrim: Color,
)

private val DarkExtended = TelepicExtendedColors(
    mediaBackdrop = Color(0xFF07080A),
    scrim = Color(0x99000000),
)

private val LightExtended = TelepicExtendedColors(
    mediaBackdrop = Color(0xFFEDF0F5),
    scrim = Color(0x66000000),
)

val LocalTelepicColors = staticCompositionLocalOf { DarkExtended }

/**
 * Root of the Telepic design system.
 *
 * Wraps [MaterialTheme] and additionally provides the centralized spacing and extended
 * color tokens. Dark is the product default; the caller decides [darkTheme] based on the
 * user's persisted theme preference.
 */
@Composable
fun TelepicTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) TelepicDarkColorScheme else TelepicLightColorScheme
    val extended = if (darkTheme) DarkExtended else LightExtended

    CompositionLocalProvider(
        LocalSpacing provides TelepicSpacing(),
        LocalTelepicColors provides extended,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TelepicTypography,
            shapes = TelepicShapes,
            content = content,
        )
    }
}

/** Convenience accessors for the centralized Telepic design tokens. */
object TelepicTokens {
    val spacing: TelepicSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val colors: TelepicExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTelepicColors.current
}
