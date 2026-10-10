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
 *
 * Not every literal is a bug: `Color.White` text and icons painted directly on top of photo
 * content stay inline at call sites, because they belong to the media overlay, not the theme.
 */
@Immutable
data class TelepicExtendedColors(
    /** Deep, near-black backdrop that makes media pop. */
    val mediaBackdrop: Color,
    /** Scrim used behind overlays on media. */
    val scrim: Color,
    /**
     * Full-screen immersive backdrop behind the Viewer (photos/videos). Deliberate pure black in
     * BOTH schemes — the viewer frames media, not surfaces; never a raw literal at call sites.
     */
    val viewerBackdrop: Color,
    /** Translucent chrome bars overlaid on media (viewer top/bottom bars). Media-backed, so the
     * same dark veil in both schemes — [scrim] is for overlays on app surfaces, this for on-image. */
    val chromeScrim: Color,
    /** The favorite/star accent, consistent across schemes and screens. */
    val favoriteAmber: Color,
)

private val DarkExtended = TelepicExtendedColors(
    mediaBackdrop = Color(0xFF07080A),
    scrim = Color(0x99000000),
    viewerBackdrop = Color.Black,
    chromeScrim = Color(0x88000000),
    favoriteAmber = Color(0xFFFFC53D),
)

private val LightExtended = TelepicExtendedColors(
    mediaBackdrop = Color(0xFFEDF0F5),
    scrim = Color(0x66000000),
    viewerBackdrop = Color.Black,
    chromeScrim = Color(0x88000000),
    favoriteAmber = Color(0xFFFFC53D),
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
