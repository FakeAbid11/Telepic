package com.telepic.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

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
     * same dark veil in both schemes — [scrim] is for overlays on app surfaces, this for on-image.
     *
     * Held at 70% black because the chrome's text has to stay legible over the *worst* case, not the
     * average one: a white region of a photo. White text on 53% black measures 4.48:1 there — under
     * the 4.5:1 AA floor — and the 70%-alpha source label under it fell to 3.08:1. At 70% those
     * become 8.45:1 and 5.10:1. The bars fade this out towards the media rather than ending it on a
     * hard edge (see the gradients in `ViewerScreen`). */
    val chromeScrim: Color,
    /** The favorite/star accent, consistent across schemes and screens. */
    val favoriteAmber: Color,
    /** Error text drawn directly on the viewer's black backdrop. Deliberately NOT
     * `colorScheme.error`: the light scheme's `error` is a dark red that measures 3.25:1 on black,
     * and the viewer backdrop is black in *both* schemes, so the on-media error tone has to be a
     * light red that is scheme-independent (like [favoriteAmber]). Measures 9.20:1 on black. */
    val mediaErrorText: Color,
    /** Success text drawn directly on the viewer's black backdrop — the same constraint as
     * [mediaErrorText], and the reason this is not `colorScheme.primary` or a Material green: the
     * backdrop is black in both schemes, so the tone has to be light and scheme-independent.
     * Measures 13.1:1 on black, comfortably clear of the AA floor even over the 70% chrome scrim.
     * Used for "Backed up", which must read as *done* rather than as another thing to tap. */
    val mediaSuccessText: Color,
    /** Warning text/glyph drawn directly on the viewer's black backdrop — the same constraint as
     * [mediaErrorText], for the "stalled / retry-exhausted" backup state. Without it that state used
     * `colorScheme.tertiary`, which in the light scheme is a mid-blue (#3F6C99) measuring ~2.5:1 on
     * black — under the 3:1 floor for a graphical marker, so the warning was invisible in light mode
     * even though the backdrop is identical in both schemes. Measures ~12:1 on black. */
    val mediaWarningText: Color,
    /**
     * Fill of a "glass" control chip overlaid on media (the Viewer's back button, its grouped action
     * pill, the mute control). Scheme-independent like the other on-media tokens: the backdrop is
     * black in both schemes, so a translucent black veil reads the same either way. Held lighter than
     * [chromeScrim] because a *control* has to feel tappable and be read, whereas the scrim only has
     * to keep static text legible.
     */
    val glassFill: Color,
    /** Hairline border that gives a glass chip its edge over a busy photo without a solid outline. */
    val glassBorder: Color,
)

private val DarkExtended = TelepicExtendedColors(
    mediaBackdrop = Color(0xFF07080A),
    scrim = Color(0x99000000),
    viewerBackdrop = Color.Black,
    chromeScrim = Color(0xB3000000),
    favoriteAmber = Color(0xFFFFC53D),
    mediaErrorText = Color(0xFFFF8A80),
    mediaSuccessText = Color(0xFF7FE0A6),
    mediaWarningText = Color(0xFFFFB74D),
    glassFill = Color(0x59000000),
    glassBorder = Color(0x1FFFFFFF),
)

private val LightExtended = TelepicExtendedColors(
    mediaBackdrop = Color(0xFFEDF0F5),
    scrim = Color(0x66000000),
    viewerBackdrop = Color.Black,
    chromeScrim = Color(0xB3000000),
    favoriteAmber = Color(0xFFFFC53D),
    mediaErrorText = Color(0xFFFF8A80),
    mediaSuccessText = Color(0xFF7FE0A6),
    mediaWarningText = Color(0xFFFFB74D),
    glassFill = Color(0x59000000),
    glassBorder = Color(0x1FFFFFFF),
)

val LocalTelepicColors = staticCompositionLocalOf { DarkExtended }

/**
 * Whether the device has been told to reduce motion (`Settings > Accessibility > Remove animations`,
 * i.e. `ANIMATOR_DURATION_SCALE == 0`). Compose does not consult it on its own, so every decorative
 * transition in the app keeps running for those users.
 *
 * Adopted where the motion is purely cosmetic (the date rail's fade and thumb glide) rather than
 * everywhere at once: a transition that carries information — the scrubber following the finger, the
 * backup badge changing — should still transition, and each site has to be judged. [telepicMotionSpec]
 * is the seam for that judgement.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** The spec a decorative animation should use: none at all when the user asked for less motion. */
@Composable
fun <T : Any> telepicMotionSpec(): AnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring()

/**
 * Navigation motion timing, in milliseconds — one tune for every route change.
 *
 * navigation-compose's default is a 700 ms cross-fade, which reads as a muddy double-exposure
 * between a bright grid and the Viewer's pure-black backdrop. These are short on purpose: the
 * tile→image shared-element zoom carries the eye, and the route fade only has to get out of its way.
 */
const val NAV_FADE_ENTER_MILLIS = 220
const val NAV_FADE_EXIT_MILLIS = 160

/** How long the shared tile→image zoom runs; matched to the fade so neither outlives the other. */
const val SHARED_ELEMENT_MILLIS = 300

/**
 * The one spacing instance the app uses.
 *
 * A single instance rather than a fresh `TelepicSpacing()` per theme composition: the tokens never
 * change, and `staticCompositionLocalOf` does not recompose its readers when the value changes — so
 * a new instance each time would leave readers holding a stale one for no benefit.
 */
private val DefaultSpacing = TelepicSpacing()

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
    // Re-read on every configuration change: the animator-duration scale that drives this is itself
    // a configuration value, so a user who flips "Remove animations" in system settings while
    // Telepic is backgrounded must not keep the stale answer until the next process start. `null`
    // on a ROM that will not answer simply means "animate normally", which is the safe direction
    // for a cosmetic switch.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val reduceMotion = remember(configuration) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    CompositionLocalProvider(
        LocalSpacing provides DefaultSpacing,
        LocalTelepicColors provides extended,
        LocalReduceMotion provides reduceMotion,
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
