package com.telepic.navigation

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
import com.telepic.R

/**
 * The five primary Telepic destinations shown in bottom navigation.
 *
 * Kept as a pure enum (no Compose types in the constructor) so it is trivially testable
 * on the JVM and so later phases can add contextual screens without touching this core set.
 */
enum class TelepicDestination(
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

        fun fromRoute(route: String?): TelepicDestination =
            entries.firstOrNull { it.route == route } ?: Start

        /**
         * The top-level tab a route belongs to, or null when the route is a pushed/detail screen
         * (viewer, album contents, organization, backup). Used to highlight the correct tab and hide
         * the bottom bar on detail screens instead of defaulting every route to [Start].
         */
        fun topLevelOf(route: String?): TelepicDestination? =
            entries.firstOrNull { it.route == route }
    }
}

/** Unselected (outlined) icon for a destination. */
val TelepicDestination.icon: ImageVector
    get() = when (this) {
        TelepicDestination.Photos -> Icons.Outlined.PhotoLibrary
        TelepicDestination.Cloud -> Icons.Outlined.CloudQueue
        TelepicDestination.Albums -> Icons.Outlined.Collections
        TelepicDestination.Map -> Icons.Outlined.Map
        TelepicDestination.Settings -> Icons.Outlined.Settings
    }

/** Selected (filled) icon for a destination. */
val TelepicDestination.selectedIcon: ImageVector
    get() = when (this) {
        TelepicDestination.Photos -> Icons.Filled.PhotoLibrary
        TelepicDestination.Cloud -> Icons.Filled.Cloud
        TelepicDestination.Albums -> Icons.Filled.Collections
        TelepicDestination.Map -> Icons.Filled.Map
        TelepicDestination.Settings -> Icons.Filled.Settings
    }
