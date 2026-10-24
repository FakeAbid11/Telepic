package com.telepic.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * Shared top-level tab navigation. Single-top, saves/restores per-tab state so a tab keeps its
 * scroll/paging state, and keeps the start destination on the stack so Back from any tab returns
 * home instead of exiting.
 *
 * Because the graph is flat (detail screens such as the Viewer sit in the SAME stack as their
 * originating tab), `restoreState = true` can restore a pushed detail entry as the top of a tab's
 * stack. Tapping a tab must always show that tab's ROOT, so after navigating we pop anything above
 * the tab route back into place. Re-tapping the already-selected tab is a no-op here.
 */
fun NavHostController.navigateToTopLevel(destination: TelepicDestination) {
    val route = destination.route
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
    // A tab tap must land on the tab root, never on a restored detail screen.
    // inclusive = false keeps the tab root and pops the detail entries above it.
    if (currentDestination?.route != route) {
        popBackStack(route, inclusive = false)
    }
}

/**
 * Open Settings from the gear in a screen's header.
 *
 * Deliberately **not** [navigateToTopLevel]: Settings is a destination, not a bottom-bar tab, and the
 * top-level form pops to the start destination with `saveState`/`restoreState`. Opening the gear while
 * on Cloud and pressing Back therefore landed the user on Photos — the tab they were in was gone, and
 * so was the context they opened Settings for. A plain push leaves the originating screen underneath,
 * so Back returns to it. `launchSingleTop` keeps double-tapping the gear from stacking Settings.
 */
fun NavHostController.navigateToSettings(settingsRoute: String = TelepicDestination.Settings.route) {
    navigate(settingsRoute) {
        launchSingleTop = true
    }
}

/**
 * Open the Map from the Viewer's "Show on map" action.
 *
 * Deliberately **not** [navigateToTopLevel]: the Map is a tab, but this is a hand-off *from* a detail
 * screen, so the top-level form's `popUpTo(start){saveState}` popped the Viewer and Back from the map
 * landed on Photos — the photo the user was looking at was gone. A plain push keeps the Viewer
 * underneath so Back returns to it, exactly like [navigateToSettings].
 *
 * When the Map is already on the stack the push is wrong: the hand-off then started *on* the Map tab,
 * so pushing would leave Map → Viewer → Map and Back would land on the Viewer the user had just left.
 * Popping back to the existing entry instead returns to the tab they came from. `launchSingleTop`
 * still covers the case where the Map is already the current destination.
 */
fun NavHostController.navigateToMap(mapRoute: String = TelepicDestination.Map.route) {
    if (popBackStack(mapRoute, inclusive = false)) return
    navigate(mapRoute) {
        launchSingleTop = true
    }
}
