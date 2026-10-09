package com.telepix.navigation

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
fun NavHostController.navigateToTopLevel(destination: TelepixDestination) {
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
