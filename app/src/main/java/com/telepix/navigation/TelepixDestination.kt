package com.telepix.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.telepix.R

/**
 * The five primary Telepix destinations shown in bottom navigation.
 *
 * Kept as a pure enum (no Compose types in the constructor) so it is trivially testable
 * on the JVM and so later phases can add contextual screens without touching this core set.
 */
enum class TelepixDestination(
    val route: String,
    @StringRes val labelRes: Int,
) {
    Photos("photos", R.string.nav_photos),
    Cloud("cloud", R.string.nav_cloud),
    Albums("albums", R.string.nav_albums),
    Map("map", R.string.nav_map),
    Settings("settings", R.string.nav_settings),
    ;

    companion object {
        /** Destination shown on first launch. */
        val Start = Photos

        fun fromRoute(route: String?): TelepixDestination =
            entries.firstOrNull { it.route == route } ?: Start

        /**
         * The top-level tab a route belongs to, or null when the route is a pushed/detail screen
         * (viewer, album contents, organization, backup). Used to highlight the correct tab and hide
         * the bottom bar on detail screens instead of defaulting every route to [Start].
         */
        fun topLevelOf(route: String?): TelepixDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/** Unselected (outlined) icon for a destination. */
val TelepixDestination.icon: ImageVector
    get() = when (this) {
        TelepixDestination.Photos -> Icons.Outlined.PhotoLibrary
        TelepixDestination.Cloud -> Icons.Outlined.CloudQueue
        TelepixDestination.Albums -> Icons.Outlined.Collections
        TelepixDestination.Map -> Icons.Outlined.Map
        TelepixDestination.Settings -> Icons.Outlined.Settings
    }

/** Selected (filled) icon for a destination. */
val TelepixDestination.selectedIcon: ImageVector
    get() = when (this) {
        TelepixDestination.Photos -> Icons.Filled.PhotoLibrary
        TelepixDestination.Cloud -> Icons.Filled.Cloud
        TelepixDestination.Albums -> Icons.Filled.Collections
        TelepixDestination.Map -> Icons.Filled.Map
        TelepixDestination.Settings -> Icons.Filled.Settings
    }
