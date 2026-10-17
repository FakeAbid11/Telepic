package com.telepic.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the fix for the swiping defect: a viewer opened from an album, a folder or a collection must
 * rebuild its real [ViewerScope] from the route, not collapse to [ViewerScope.Global].
 *
 * The routes carry their context as *optional query args*. Navigation only reads a named query value
 * when the pattern spells the key out (`?key={key}`); a bare `?{key}` is instead its "single query
 * param value only" form, which hands the entire raw query string to every declared argument — the
 * Long args fail to parse back to their defaults and the scope resolves to Global, so swiping walked
 * the whole library.
 *
 * Uses a NavHost mirroring the real graph's viewer/album destinations and drives the exact route
 * strings the app builds. Robolectric so Compose + NavController run on the JVM main looper.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ViewerRouteScopeTest {

    @get:Rule
    val rule = createComposeRule()

    private var controller: NavHostController? = null

    private fun setNav() {
        rule.setContent {
            val nc = rememberNavController()
            controller = nc
            NavHost(navController = nc, startDestination = TelepicDestination.Photos.route) {
                composable(TelepicDestination.Photos.route) { Text("photos") }
                composable(
                    route = ViewerRoute.LOCAL,
                    arguments = listOf(
                        navArgument(ViewerRoute.ARG_MEDIA_ID) { type = NavType.LongType },
                        navArgument(ViewerRoute.ARG_BUCKET_ID) {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                        navArgument(ViewerRoute.ARG_ORG_KIND) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(ViewerRoute.ARG_ALBUM_ID) {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) { Text("viewer") }
                composable(
                    route = AlbumRoute.CUSTOM_PATTERN,
                    arguments = listOf(
                        navArgument(AlbumRoute.ARG_ALBUM_ID) { type = NavType.LongType },
                        navArgument(AlbumRoute.ARG_NAME) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { Text("customalbum") }
            }
        }
        rule.waitForIdle()
    }

    /** Navigates to [route] and rebuilds the [ViewerScope] exactly the way the nav graph does. */
    private fun scopeFor(route: String): ViewerScope {
        val nav = controller!!
        nav.navigate(route)
        rule.waitForIdle()
        val args = nav.currentBackStackEntry!!.arguments!!
        return ViewerRoute.scopeOf(
            bucketId = args.getLong(ViewerRoute.ARG_BUCKET_ID),
            orgKind = OrganizationRoute.kindOf(args.getString(ViewerRoute.ARG_ORG_KIND)),
            albumId = args.getLong(ViewerRoute.ARG_ALBUM_ID),
        )
    }

    private fun argsFor(route: String) = run {
        val nav = controller!!
        nav.navigate(route)
        rule.waitForIdle()
        nav.currentBackStackEntry!!.arguments!!
    }

    @Test
    fun `a plain local route walks the global timeline`() {
        setNav()
        assertEquals(ViewerScope.Global, scopeFor(ViewerRoute.local(7)))
    }

    @Test
    fun `a folder album keeps its bucket scope`() {
        setNav()
        assertEquals(ViewerScope.Bucket(42L), scopeFor(ViewerRoute.localInBucket(7, 42L)))
    }

    @Test
    fun `a collection keeps its collection scope`() {
        setNav()
        assertEquals(
            ViewerScope.Collection(OrganizationKind.FAVORITES),
            scopeFor(ViewerRoute.localInCollection(7, OrganizationKind.FAVORITES)),
        )
    }

    @Test
    fun `a user album keeps its album scope`() {
        setNav()
        assertEquals(ViewerScope.Album(42L), scopeFor(ViewerRoute.localInCustomAlbum(7, 42L)))
    }

    @Test
    fun `a scoped route leaves the other contexts unset`() {
        setNav()
        val args = argsFor(ViewerRoute.localInBucket(7, 42L))
        assertEquals(42L, args.getLong(ViewerRoute.ARG_BUCKET_ID))
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_ALBUM_ID))
        assertNull(args.getString(ViewerRoute.ARG_ORG_KIND))
    }

    @Test
    fun `the custom album name travels decoded as an initial value`() {
        setNav()
        val args = argsFor(AlbumRoute.createCustom(5L, "My Trip"))
        assertEquals(5L, args.getLong(AlbumRoute.ARG_ALBUM_ID))
        assertEquals("My Trip", args.getString(AlbumRoute.ARG_NAME))
    }
}
