package com.telepix.navigation

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
            NavHost(navController = nc, startDestination = TelepixDestination.Photos.route) {
                composable(TelepixDestination.Photos.route) { Text("photos") }
                composable(TelepixDestination.Cloud.route) { Text("cloud") }
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
        nav.navigateToTopLevel(TelepixDestination.Cloud)
        rule.waitForIdle()
        nav.navigateToTopLevel(TelepixDestination.Photos)
        rule.waitForIdle()

        assertEquals(TelepixDestination.Photos.route, nav.currentDestination?.route)
        // The bar's selection logic resolves the current route back to the Photos tab (bar visible).
        assertEquals(TelepixDestination.Photos, TelepixDestination.topLevelOf(nav.currentDestination?.route))
    }

    @Test
    fun `tapping a different tab shows that tab root`() {
        setNav()
        val nav = controller!!
        nav.navigateToTopLevel(TelepixDestination.Cloud)
        rule.waitForIdle()
        assertEquals(TelepixDestination.Cloud.route, nav.currentDestination?.route)
    }
}
