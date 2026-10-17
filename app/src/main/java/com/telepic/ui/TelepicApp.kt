package com.telepic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.telepic.navigation.TelepicDestination
import com.telepic.navigation.TelepicNavHost
import com.telepic.navigation.icon
import com.telepic.navigation.navigateToTopLevel
import com.telepic.navigation.selectedIcon
import com.telepic.di.AppContainer
import com.telepic.settings.ThemeMode
import com.telepic.ui.theme.LocalReduceMotion
import com.telepic.ui.theme.NAV_FADE_ENTER_MILLIS
import com.telepic.ui.theme.NAV_FADE_EXIT_MILLIS
import com.telepic.ui.theme.TelepicTokens

private const val NAV_PILL_RADIUS_DP = 28
private const val NAV_PILL_ELEVATION_DP = 10

/**
 * Width at or above which the tabs move to a leading `NavigationRail`.
 *
 * Material's "expanded" breakpoint. Read from `LocalConfiguration` rather than pulling in
 * `material3-window-size-class` for a single threshold — the app needs one number, not a size class.
 */
private const val EXPANDED_WIDTH_DP = 600

/**
 * The Telepic application shell: a Material 3 [Scaffold] with a bottom navigation bar for
 * [TelepicDestination.tabs] and the navigation host filling the remaining space.
 *
 * Settings is not one of those tabs — it is reached from the gear in the top-right corner of every
 * tab — so the bar is hidden on it rather than shown with no tab selected.
 */
@Composable
fun TelepicApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val spacing = TelepicTokens.spacing
    // Only a bottom-bar tab route selects a tab; detail/immersive screens (viewer, album contents,
    // organization, backup) and Settings resolve to null so the bar neither mis-highlights Photos nor
    // overlays them.
    val currentTab = TelepicDestination.topLevelOf(route)
    // A tablet or an unfolded foldable gets a leading rail instead of a phone bar stretched across
    // the full width — the previous behaviour, where four tabs sat a hand's width apart at the
    // bottom of a 1280dp screen.
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP

    Scaffold(
        modifier = modifier,
        bottomBar = {
            // Faded and slid rather than popped: the Viewer hides this bar, and its instant
            // disappearance used to yank the grid up by the bar's height in a single frame.
            AnimatedVisibility(
                visible = currentTab != null && !expanded,
                enter = fadeIn(tween(NAV_FADE_ENTER_MILLIS)) +
                    slideInVertically(tween(NAV_FADE_ENTER_MILLIS)) { fullHeight -> fullHeight / 2 },
                exit = fadeOut(tween(NAV_FADE_EXIT_MILLIS)) +
                    slideOutVertically(tween(NAV_FADE_EXIT_MILLIS)) { fullHeight -> fullHeight / 2 },
            ) {
                // Google Photos-style floating bar: a rounded, elevated pill that sits above the
                // system navigation bar with side/bottom margins. Selection stays a tonal pill.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = spacing.lg)
                        .padding(top = spacing.sm, bottom = spacing.md),
                ) {
                    NavigationBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(NAV_PILL_ELEVATION_DP.dp, RoundedCornerShape(NAV_PILL_RADIUS_DP.dp))
                            .clip(RoundedCornerShape(NAV_PILL_RADIUS_DP.dp)),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        TelepicDestination.tabs.forEach { destination ->
                            val selected = destination == currentTab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(destination) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        // The Viewer is immersive: its black backdrop and media draw edge-to-edge behind the
        // system bars (chrome pads itself with the insets), so it must not receive the Scaffold's
        // content padding — that padding is what used to leave a light window-background strip
        // above the full-screen media. Every other route keeps the inset padding.
        //
        // Animated, not switched: the inset collapses to zero as the Viewer is entered and swells
        // back on return, so the outgoing grid and the incoming Viewer share one shrinking margin
        // instead of the grid snapping up by the bar's height in a single frame.
        val reduceMotion = LocalReduceMotion.current
        val viewerImmersion by animateFloatAsState(
            targetValue = if (route?.startsWith("viewer/") == true) 1f else 0f,
            animationSpec = if (reduceMotion) snap() else tween(NAV_FADE_ENTER_MILLIS),
            label = "viewerImmersion",
        )
        val layoutDirection = LocalLayoutDirection.current
        fun inset(value: Dp): Dp = value * (1f - viewerImmersion)
        val navPadding = PaddingValues(
            start = inset(innerPadding.calculateStartPadding(layoutDirection)),
            top = inset(innerPadding.calculateTopPadding()),
            end = inset(innerPadding.calculateEndPadding(layoutDirection)),
            bottom = inset(innerPadding.calculateBottomPadding()),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            // The rail takes its own system-bar insets (the default `NavigationRail` behaviour), and
            // `navPadding` still carries them to the content, so neither hides under a bar. Faded and
            // expanded rather than popped, for the same no-jump reason as the bottom bar.
            AnimatedVisibility(
                visible = currentTab != null && expanded,
                enter = fadeIn(tween(NAV_FADE_ENTER_MILLIS)) +
                    expandHorizontally(tween(NAV_FADE_ENTER_MILLIS), expandFrom = Alignment.Start),
                exit = fadeOut(tween(NAV_FADE_EXIT_MILLIS)) +
                    shrinkHorizontally(tween(NAV_FADE_EXIT_MILLIS), shrinkTowards = Alignment.Start),
            ) {
                NavigationRail {
                    TelepicDestination.tabs.forEach { destination ->
                        val selected = destination == currentTab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
            TelepicNavHost(
                navController = navController,
                container = container,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(navPadding)
                    .fillMaxSize(),
            )
        }
    }
}
