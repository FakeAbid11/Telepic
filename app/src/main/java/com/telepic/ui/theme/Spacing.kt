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
    val xxxl: Dp = 48.dp,
    /** Standard horizontal screen margin. */
    val screenMargin: Dp = 16.dp,
    /** Comfortable minimum touch target. */
    val touchTarget: Dp = 48.dp,
    /** Grid gutter used by media grids. */
    val gridGutter: Dp = 2.dp,
    /** Corner radius applied to media grid cells. */
    val gridItemRadius: Dp = 10.dp,
    /** Reserved right-edge padding so the date rail never covers grid media. */
    val railGutter: Dp = 56.dp,
    /** Width of the date-rail column (kept at the Material touch-target width). */
    val railWidth: Dp = 44.dp,
)

val LocalSpacing = staticCompositionLocalOf { TelepicSpacing() }
