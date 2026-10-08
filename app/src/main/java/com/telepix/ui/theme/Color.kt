package com.telepix.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Telepix brand palette.
 *
 * The product identity is Blue + Black + White. Blue is used to *guide* interaction
 * (primary actions, selection, active navigation) rather than to flood every surface,
 * so that media content stays visually dominant.
 */
object TelepixBrand {
    /** Core Telepix blue. */
    val Blue = Color(0xFF2F6BF0)
    val BlueLight = Color(0xFF7FA9FF)
    val BlueDark = Color(0xFF163B8C)
    val BlueContainerDark = Color(0xFF132A55)

    /** Neutral dark surfaces (deep black, not pure #000 to avoid harsh contrast). */
    val BlackSurface = Color(0xFF0B0D10)
    val BlackElevated = Color(0xFF121519)
    val BlackVariant = Color(0xFF1A1E24)

    /** Neutral light surfaces. */
    val White = Color(0xFFFFFFFF)
    val WhiteVariant = Color(0xFFF1F3F7)

    /** Neutral text / outline tones. */
    val TextDark = Color(0xFF0F1216)
    val TextLight = Color(0xFFE7EAF0)
    val OutlineDark = Color(0xFF2A2F37)
    val OutlineLight = Color(0xFFC4C9D2)
}

/** Material 3 dark color scheme — the Telepix default theme. */
val TelepixDarkColorScheme = darkColorScheme(
    primary = TelepixBrand.BlueLight,
    onPrimary = Color(0xFF08193D),
    primaryContainer = TelepixBrand.BlueContainerDark,
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFFB7C4DA),
    onSecondary = Color(0xFF111823),
    secondaryContainer = Color(0xFF20283A),
    onSecondaryContainer = Color(0xFFDCE6F5),
    tertiary = Color(0xFF9BB7D6),
    onTertiary = Color(0xFF0E1A26),
    background = TelepixBrand.BlackSurface,
    onBackground = TelepixBrand.TextLight,
    surface = TelepixBrand.BlackSurface,
    onSurface = TelepixBrand.TextLight,
    surfaceVariant = TelepixBrand.BlackVariant,
    onSurfaceVariant = Color(0xFFC2C8D2),
    surfaceContainer = TelepixBrand.BlackElevated,
    surfaceContainerHigh = Color(0xFF171B21),
    outline = TelepixBrand.OutlineDark,
    outlineVariant = Color(0xFF20242B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3B0907),
)

/** Material 3 light color scheme — intentionally designed, not an inversion of dark. */
val TelepixLightColorScheme = lightColorScheme(
    primary = TelepixBrand.Blue,
    onPrimary = TelepixBrand.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF04163D),
    secondary = Color(0xFF4A5871),
    onSecondary = TelepixBrand.White,
    secondaryContainer = Color(0xFFDDE6F5),
    onSecondaryContainer = Color(0xFF141B28),
    tertiary = Color(0xFF3F6C99),
    onTertiary = TelepixBrand.White,
    background = TelepixBrand.White,
    onBackground = TelepixBrand.TextDark,
    surface = TelepixBrand.White,
    onSurface = TelepixBrand.TextDark,
    surfaceVariant = TelepixBrand.WhiteVariant,
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFEFF2F7),
    outline = TelepixBrand.OutlineLight,
    outlineVariant = Color(0xFFE0E3E9),
    error = Color(0xFFBA1A1A),
    onError = TelepixBrand.White,
)
