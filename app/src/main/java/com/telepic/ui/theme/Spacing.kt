package com.telepic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized spacing / dimension tokens.
 *
 * Screens reference these tokens rather than scattering arbitrary dp values, so the
 * whole app's rhythm can be retuned from one place.
 */
@Immutable
data class TelepicSpacing(
    /** Tightest gap — inline elements, icon-to-label. */
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Standard horizontal screen margin. */
    val screenMargin: Dp = 16.dp,
    /** Comfortable minimum touch target. */
    val touchTarget: Dp = 48.dp,
    /** Grid gutter used by media grids. */
    val gridGutter: Dp = 2.dp,
    /** Corner radius applied to media grid cells. */
    val gridItemRadius: Dp = 10.dp,
    /** Uniform adaptive min-size for every media tile grid (timeline, albums, collections, cloud). */
    val gridTileMinSize: Dp = 108.dp,
    /**
     * Widest a column of *prose-like* content is allowed to grow on a large screen.
     *
     * The media grids must stay full-bleed — an album is a wall of thumbnails and widening it shows
     * more photos, which is the point. A list of settings rows is not: on a tablet (or a phone in
     * landscape) an unbounded row puts a label and its summary a hand's width apart, and the eye has
     * to travel the full width to read one sentence. Capping the column keeps a readable measure and
     * the surrounding surface fills the rest. Only the document-style screens (Settings, Backup
     * Center) use this; see the note on `mediaBackdrop` in `TelepicExtendedColors`.
     */
    val contentMaxWidth: Dp = 640.dp,
) {
    /**
     * Height of one of the Viewer's chrome bars — the bar's own vertical padding plus its 48dp
     * content row — *excluding* the system-bar inset the bar also covers.
     *
     * The video surface reserves exactly this much at each end (see `VideoViewer`). media3 lays its
     * controller out against `PlayerView`'s edges, so a window-sized `PlayerView` slid the
     * controller's own bottom bar, its progress line and its whole-surface dim underneath the
     * chrome. Deriving both sides from this one value is what keeps them from drifting apart: the
     * bars build their height from [sm] and [touchTarget], and this is the same sum.
     */
    val chromeBarHeight: Dp get() = sm + touchTarget + sm
}

val LocalSpacing = staticCompositionLocalOf { TelepicSpacing() }
