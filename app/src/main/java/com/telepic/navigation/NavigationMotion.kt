package com.telepic.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.telepic.ui.theme.LocalReduceMotion
import com.telepic.ui.theme.NAV_FADE_ENTER_MILLIS
import com.telepic.ui.theme.NAV_FADE_EXIT_MILLIS

/**
 * The four route transitions a [androidx.navigation.compose.NavHost] animates between destinations.
 *
 * Bundled so the whole graph shares one tune rather than each destination inventing its own, and so
 * `LocalReduceMotion` is read in composition — the NavHost's transition lambdas are not composable
 * and cannot read it themselves.
 */
@Immutable
data class NavigationTransitions(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val popEnter: EnterTransition,
    val popExit: ExitTransition,
)

/**
 * The app's route motion: a short cross-fade, or none at all when the user asked for less motion
 * (parity with the shared element, which also stands down under [LocalReduceMotion]).
 *
 * A plain fade — not a slide — because the tile→image shared-element zoom is the motion the Viewer
 * transition is *about*; a second, competing slide would fight it. Fading is the same spec forward
 * and on pop, so opening and closing a detail screen feel like one gesture reversed.
 */
@Composable
fun navigationTransitions(): NavigationTransitions {
    val reduceMotion = LocalReduceMotion.current
    // Remembered so a recomposition of the graph does not hand the NavHost four fresh transition
    // objects (and re-create the specs behind them) for a change that did not happen.
    return remember(reduceMotion) {
        if (reduceMotion) {
            NavigationTransitions(
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                popEnter = EnterTransition.None,
                popExit = ExitTransition.None,
            )
        } else {
            val enter = fadeIn(animationSpec = tween(NAV_FADE_ENTER_MILLIS, easing = LinearOutSlowInEasing))
            val exit = fadeOut(animationSpec = tween(NAV_FADE_EXIT_MILLIS, easing = LinearOutSlowInEasing))
            NavigationTransitions(enter = enter, exit = exit, popEnter = enter, popExit = exit)
        }
    }
}
