package com.telepic.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Telepic brand palette.
 *
 * The product identity is Blue + Black + White. Blue is used to *guide* interaction
 * (primary actions, selection, active navigation) rather than to flood every surface,
 * so that media content stays visually dominant.
 */
object TelepicBrand {
    /** Core Telepic blue. */
    val Blue = Color(0xFF2F6BF0)
    val BlueLight = Color(0xFF7FA9FF)
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

    /**
     * Outline tones.
     *
     * These carry **meaning**, not decoration: `outline` paints the unselected radio glyph on the
     * onboarding choice cards, so the user has to be able to see which option they are on.
     *
     * The values they replaced measured 1.45:1 (dark) and 1.66:1 (light) against their own
     * surface — far under the 3:1 floor WCAG 1.4.11 sets for a graphical object, which left the
     * unselected radio button effectively invisible. These measure 3.73:1 and 4.09:1: still quiet
     * enough to read as an outline, strong enough to be seen.
     */
    val OutlineDark = Color(0xFF656D79)
    val OutlineLight = Color(0xFF767E8C)
}

/** Material 3 dark color scheme — the Telepic default theme. */
val TelepicDarkColorScheme = darkColorScheme(
    primary = TelepicBrand.BlueLight,
    onPrimary = Color(0xFF08193D),
    primaryContainer = TelepicBrand.BlueContainerDark,
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFFB7C4DA),
    onSecondary = Color(0xFF111823),
    secondaryContainer = Color(0xFF20283A),
    onSecondaryContainer = Color(0xFFDCE6F5),
    tertiary = Color(0xFF9BB7D6),
    onTertiary = Color(0xFF0E1A26),
    background = TelepicBrand.BlackSurface,
    onBackground = TelepicBrand.TextLight,
    surface = TelepicBrand.BlackSurface,
    onSurface = TelepicBrand.TextLight,
    surfaceVariant = TelepicBrand.BlackVariant,
    onSurfaceVariant = Color(0xFFC2C8D2),
    surfaceContainer = TelepicBrand.BlackElevated,
    surfaceContainerHigh = Color(0xFF171B21),
    outline = TelepicBrand.OutlineDark,
    outlineVariant = Color(0xFF20242B),
    // The TransientMessageBar (the "Moved to trash · Undo" bar) paints its action label in
    // `inversePrimary`. Neither scheme set it, so it fell back to the Material baseline purple
    // (#D0BCFF / #6750A4) — a violet Undo in a blue-and-black app. The dark scheme's inverseSurface
    // is a *light* surface, so its action tone has to be dark to read on it; the light scheme is the
    // mirror. Both measure >4.5:1 against their inverse surface.
    inversePrimary = Color(0xFF1B4FD8),
    // `inverseSurface` / `inverseOnSurface` are the "contrasts with its surroundings" pair, and three
    // shipped surfaces already draw on them: the Undo bar, the date-rail drag bubble, and (now) the
    // map's floating controls. Neither scheme set them, so all three were rendering on Material's M2
    // baseline (#E6E1E5 / #313033) — the same purple-grey leak this file already documents for
    // `inversePrimary`. Set explicitly to the app's own tones; the pair measures ~15:1.
    inverseSurface = Color(0xFFE7EAF0),
    inverseOnSurface = Color(0xFF0B0D10),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3B0907),
)

/** Material 3 light color scheme — intentionally designed, not an inversion of dark. */
val TelepicLightColorScheme = lightColorScheme(
    primary = TelepicBrand.Blue,
    onPrimary = TelepicBrand.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF04163D),
    secondary = Color(0xFF4A5871),
    onSecondary = TelepicBrand.White,
    secondaryContainer = Color(0xFFDDE6F5),
    onSecondaryContainer = Color(0xFF141B28),
    tertiary = Color(0xFF3F6C99),
    onTertiary = TelepicBrand.White,
    background = TelepicBrand.White,
    onBackground = TelepicBrand.TextDark,
    surface = TelepicBrand.White,
    onSurface = TelepicBrand.TextDark,
    surfaceVariant = TelepicBrand.WhiteVariant,
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFEFF2F7),
    outline = TelepicBrand.OutlineLight,
    outlineVariant = Color(0xFFE0E3E9),
    // Mirror of the dark scheme's choice: this scheme's inverseSurface is *dark*, so the
    // TransientMessageBar's action label has to be a light tone. See the note above.
    inversePrimary = TelepicBrand.BlueLight,
    // The mirror of the dark scheme's pair — near-black on a light map, near-white on a dark one.
    inverseSurface = Color(0xFF14181E),
    inverseOnSurface = Color(0xFFF7F9FC),
    error = Color(0xFFBA1A1A),
    onError = TelepicBrand.White,
)
