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
 * Every Telepic destination. [tabs] is the subset shown in the bottom navigation bar.
 *
 * Settings is a destination but deliberately **not** a tab. The bar is for the places you browse and
 * stay in; Settings is somewhere you go, change something, and leave — and a permanent quarter of the
 * bar with a highlighted state that meant "you are in Settings" rather than "you are looking at your
 * library" misrepresented that. It is reached instead from the gear in the top-right corner of every
 * tab (see `SettingsAction`), which also makes it available from Cloud, Albums and Map rather than
 * only from Photos.
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

        /**
         * The destinations rendered in the bottom navigation bar, in declaration order.
         *
         * `lazy` rather than a plain initializer: the delegate is created with the companion object,
         * but the lambda only runs on first access — by which point the enum's own static
         * initialisation (and therefore `entries`) has definitely finished.
         */
        val tabs: List<TelepicDestination> by lazy { entries.filter { it != Settings } }

        fun fromRoute(route: String?): TelepicDestination =
            entries.firstOrNull { it.route == route } ?: Start

        /**
         * The bottom-bar tab a route belongs to, or null when the route is not a tab at all — either
         * a pushed/detail screen (viewer, album contents, organization, backup) or [Settings], which
         * is a full-screen destination reached from the gear.
         *
         * A null result is what hides the bottom bar. That is the intended outcome for [Settings]:
         * keeping the bar there would show it with no tab highlighted, which reads as a bug rather
         * than as "you have left the tabs".
         */
        fun topLevelOf(route: String?): TelepicDestination? =
            tabs.firstOrNull { it.route == route }
    }
}

/**
 * Unselected (outlined) icon for a destination.
 *
 * Total over [TelepicDestination.entries] because a `when` expression must be — only
 * [TelepicDestination.tabs] are ever rendered, so the [TelepicDestination.Settings] branch is not
 * reached. It stays so the compiler keeps this exhaustive if a destination is added.
 */
val TelepicDestination.icon: ImageVector
    get() = when (this) {
        TelepicDestination.Photos -> Icons.Outlined.PhotoLibrary
        TelepicDestination.Cloud -> Icons.Outlined.CloudQueue
        TelepicDestination.Albums -> Icons.Outlined.Collections
        TelepicDestination.Map -> Icons.Outlined.Map
        TelepicDestination.Settings -> Icons.Outlined.Settings
    }

/** Selected (filled) icon for a destination. See [icon] for why the [TelepicDestination.Settings] branch is unreachable. */
val TelepicDestination.selectedIcon: ImageVector
    get() = when (this) {
        TelepicDestination.Photos -> Icons.Filled.PhotoLibrary
        TelepicDestination.Cloud -> Icons.Filled.Cloud
        TelepicDestination.Albums -> Icons.Filled.Collections
        TelepicDestination.Map -> Icons.Filled.Map
        TelepicDestination.Settings -> Icons.Filled.Settings
    }
