package com.telepic.navigation

import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the bottom-nav tab-tap fix: tapping a tab must always land on that tab's ROOT screen,
 * never on a detail entry that `restoreState` resurrected onto the tab's back stack.
 *
 * Uses a minimal NavHost mirroring the real flat graph (tab roots + a pushed detail route) and
 * drives the exact repro through the shared [navigateToTopLevel]. Robolectric so Compose +
 * NavController run on the JVM main looper.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class NavToTopLevelTest {

    @get:Rule
    val rule = createComposeRule()

    private var controller: NavHostController? = null

    private fun setNav() {
        rule.setContent {
            val nc = rememberNavController()
            controller = nc
            NavHost(navController = nc, startDestination = TelepicDestination.Photos.route) {
                composable(TelepicDestination.Photos.route) { Text("photos") }
                composable(TelepicDestination.Cloud.route) { Text("cloud") }
                composable(TelepicDestination.Map.route) { Text("map") }
                composable(TelepicDestination.Settings.route) { Text("settings") }
                composable("viewer/{mediaId}") { Text("viewer") }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `tapping a tab lands on the tab root even when a detail screen was restored`() {
        setNav()
        val nav = controller!!

        // On Photos, push a detail screen; leave via another tab; then tap Photos again.
        nav.navigate("viewer/42")
        rule.waitForIdle()
        nav.navigateToTopLevel(TelepicDestination.Cloud)
        rule.waitForIdle()
        nav.navigateToTopLevel(TelepicDestination.Photos)
        rule.waitForIdle()

        assertEquals(TelepicDestination.Photos.route, nav.currentDestination?.route)
        // The bar's selection logic resolves the current route back to the Photos tab (bar visible).
        assertEquals(TelepicDestination.Photos, TelepicDestination.topLevelOf(nav.currentDestination?.route))
    }

    @Test
    fun `tapping a different tab shows that tab root`() {
        setNav()
        val nav = controller!!
        nav.navigateToTopLevel(TelepicDestination.Cloud)
        rule.waitForIdle()
        assertEquals(TelepicDestination.Cloud.route, nav.currentDestination?.route)
    }

    @Test
    fun `back from Settings returns to the screen the gear was pressed on`() {
        setNav()
        val nav = controller!!
        nav.navigateToTopLevel(TelepicDestination.Cloud)
        rule.waitForIdle()

        nav.navigateToSettings()
        rule.waitForIdle()
        assertEquals(TelepicDestination.Settings.route, nav.currentDestination?.route)

        nav.popBackStack()
        rule.waitForIdle()
        // The regression: Settings used to be navigated with the top-level helper, which pops to the
        // start destination with saveState/restoreState — so Back landed on Photos and the Cloud tab
        // the user opened the gear from was simply gone.
        assertEquals(TelepicDestination.Cloud.route, nav.currentDestination?.route)
    }

    @Test
    fun `tapping the gear twice does not stack two Settings screens`() {
        setNav()
        val nav = controller!!
        nav.navigateToSettings()
        rule.waitForIdle()
        val entriesAfterFirst = nav.currentBackStackEntry?.id

        nav.navigateToSettings()
        rule.waitForIdle()
        // launchSingleTop: the same entry is reused rather than a second Settings pushed on top, which
        // would have cost two Back presses to leave.
        assertEquals(entriesAfterFirst, nav.currentBackStackEntry?.id)
    }

    @Test
    fun `back from the map returns to the viewer it was opened from`() {
        setNav()
        val nav = controller!!

        nav.navigate("viewer/42")
        rule.waitForIdle()

        nav.navigateToMap()
        rule.waitForIdle()
        assertEquals(TelepicDestination.Map.route, nav.currentDestination?.route)

        nav.popBackStack()
        rule.waitForIdle()
        // The regression: "Show on map" used the top-level helper, which pops to the start destination —
        // so Back from the map landed on Photos and the photo the action came from was gone.
        assertEquals("viewer/{mediaId}", nav.currentDestination?.route)
    }

    @Test
    fun `requesting the map twice does not stack two maps`() {
        setNav()
        val nav = controller!!
        nav.navigate("viewer/42")
        rule.waitForIdle()

        nav.navigateToMap()
        rule.waitForIdle()
        val entriesAfterFirst = nav.currentBackStackEntry?.id

        nav.navigateToMap()
        rule.waitForIdle()
        // launchSingleTop keeps a repeat request from pushing a second map that would cost an extra Back.
        assertEquals(entriesAfterFirst, nav.currentBackStackEntry?.id)
    }
}
