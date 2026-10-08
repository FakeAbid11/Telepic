package com.telepix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended Telepix color tokens that sit on top of the Material 3 [ColorScheme].
 * These carry the product's media-first identity (deep backdrop behind photo grids,
 * scrims, etc.) so screens never hard-code raw colors.
 */
@Immutable
data class TelepixExtendedColors(
    /** Deep, near-black backdrop that makes media pop. */
    val mediaBackdrop: Color,
    /** Scrim used behind overlays on media. */
    val scrim: Color,
)

private val DarkExtended = TelepixExtendedColors(
    mediaBackdrop = Color(0xFF07080A),
    scrim = Color(0x99000000),
)

private val LightExtended = TelepixExtendedColors(
    mediaBackdrop = Color(0xFFEDF0F5),
    scrim = Color(0x66000000),
)

val LocalTelepixColors = staticCompositionLocalOf { DarkExtended }

/**
 * Root of the Telepix design system.
 *
 * Wraps [MaterialTheme] and additionally provides the centralized spacing and extended
 * color tokens. Dark is the product default; the caller decides [darkTheme] based on the
 * user's persisted theme preference.
 */
@Composable
fun TelepixTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) TelepixDarkColorScheme else TelepixLightColorScheme
    val extended = if (darkTheme) DarkExtended else LightExtended

    CompositionLocalProvider(
        LocalSpacing provides TelepixSpacing(),
        LocalTelepixColors provides extended,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TelepixTypography,
            shapes = TelepixShapes,
            content = content,
        )
    }
}

/** Convenience accessors for the centralized Telepix design tokens. */
object TelepixTokens {
    val spacing: TelepixSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val colors: TelepixExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTelepixColors.current
}
