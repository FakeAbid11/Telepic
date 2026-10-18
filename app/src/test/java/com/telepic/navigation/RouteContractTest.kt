package com.telepic.navigation

import android.os.Bundle
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the builder ↔ pattern ↔ argument contract for every parameterised route.
 *
 * The graph registers [ViewerRoute.LOCAL], [ViewerRoute.CLOUD], [AlbumRoute.PATTERN],
 * [AlbumRoute.CUSTOM_PATTERN] and [OrganizationRoute.PATTERN]; callers navigate with the builder
 * functions ([ViewerRoute.local], [AlbumRoute.create], [OrganizationRoute.create], ...). The two
 * agree only by convention: a builder typo navigates a route no destination claims, and a query
 * arg that loses its `key={key}` shape hands every declared arg the whole raw query string
 * (documented on [ViewerRoute.LOCAL]), which fails the Long parse and collapses the viewer's
 * scope. Each test therefore drives a real builder through a minimal host registering the real
 * patterns with the real argument declarations, then reads the args back exactly the way
 * [TelepicNavHost] does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class RouteContractTest {

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
                        navArgument(ViewerRoute.ARG_BUCKET_ID) { type = NavType.LongType; defaultValue = -1L },
                        navArgument(ViewerRoute.ARG_ORG_KIND) { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument(ViewerRoute.ARG_ALBUM_ID) { type = NavType.LongType; defaultValue = -1L },
                    ),
                ) { Text("viewer-local") }
                composable(
                    route = ViewerRoute.CLOUD,
                    arguments = listOf(
                        navArgument(ViewerRoute.ARG_CHAT_ID) { type = NavType.LongType },
                        navArgument(ViewerRoute.ARG_MESSAGE_ID) { type = NavType.LongType },
                    ),
                ) { Text("viewer-cloud") }
                composable(
                    route = AlbumRoute.PATTERN,
                    arguments = listOf(
                        navArgument(AlbumRoute.ARG_BUCKET_ID) { type = NavType.LongType },
                        navArgument(AlbumRoute.ARG_TITLE) { type = NavType.StringType },
                    ),
                ) { Text("album") }
                composable(
                    route = AlbumRoute.CUSTOM_PATTERN,
                    arguments = listOf(
                        navArgument(AlbumRoute.ARG_ALBUM_ID) { type = NavType.LongType },
                        navArgument(AlbumRoute.ARG_NAME) { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { Text("custom-album") }
                composable(
                    route = OrganizationRoute.PATTERN,
                    arguments = listOf(navArgument(OrganizationRoute.ARG_KIND) { type = NavType.StringType }),
                ) { Text("organization") }
            }
        }
        rule.waitForIdle()
    }

    private fun open(route: String): Bundle {
        val nav = controller!!
        nav.navigate(route)
        rule.waitForIdle()
        return nav.currentBackStackEntry?.arguments
            ?: throw AssertionError("no back stack entry for $route")
    }

    private fun currentRoute(): String? = controller!!.currentDestination?.route

    /** The same read [TelepicNavHost] performs before calling [ViewerRoute.scopeOf]. */
    private fun Bundle.viewerScope(): ViewerScope {
        val bucketId = getLong(ViewerRoute.ARG_BUCKET_ID).takeIf { it >= 0L }
        val orgKind = OrganizationRoute.kindOf(getString(ViewerRoute.ARG_ORG_KIND))
        val albumId = getLong(ViewerRoute.ARG_ALBUM_ID).takeIf { it >= 0L }
        return ViewerRoute.scopeOf(bucketId, orgKind, albumId)
    }

    @Test
    fun `a plain local route resolves to the viewer with the global timeline scope`() {
        setNav()
        val args = open(ViewerRoute.local(42L))

        assertEquals(ViewerRoute.LOCAL, currentRoute())
        assertEquals(42L, args.getLong(ViewerRoute.ARG_MEDIA_ID))
        // Registered defaults, not Bundle zeros: -1/-1 is what makes the absent query args fall
        // through to the Global timeline instead of bucket -0/album -0.
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_BUCKET_ID))
        assertNull(args.getString(ViewerRoute.ARG_ORG_KIND))
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_ALBUM_ID))
        assertEquals(ViewerScope.Global, args.viewerScope())
    }

    @Test
    fun `a bucket-scoped local route carries its bucket and nothing else`() {
        setNav()
        val args = open(ViewerRoute.localInBucket(42L, 77L))

        assertEquals(ViewerRoute.LOCAL, currentRoute())
        assertEquals(77L, args.getLong(ViewerRoute.ARG_BUCKET_ID))
        assertNull(args.getString(ViewerRoute.ARG_ORG_KIND))
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_ALBUM_ID))
        assertEquals(ViewerScope.Bucket(77L), args.viewerScope())
    }

    @Test
    fun `a collection-scoped local route carries its organization kind`() {
        setNav()
        val args = open(ViewerRoute.localInCollection(42L, OrganizationKind.ARCHIVE))

        assertEquals(ViewerRoute.LOCAL, currentRoute())
        assertEquals("archive", args.getString(ViewerRoute.ARG_ORG_KIND))
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_BUCKET_ID))
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_ALBUM_ID))
        assertEquals(ViewerScope.Collection(OrganizationKind.ARCHIVE), args.viewerScope())
    }

    @Test
    fun `an album-scoped local route carries its album id`() {
        setNav()
        val args = open(ViewerRoute.localInCustomAlbum(42L, 9L))

        assertEquals(ViewerRoute.LOCAL, currentRoute())
        assertEquals(9L, args.getLong(ViewerRoute.ARG_ALBUM_ID))
        assertEquals(-1L, args.getLong(ViewerRoute.ARG_BUCKET_ID))
        assertNull(args.getString(ViewerRoute.ARG_ORG_KIND))
        assertEquals(ViewerScope.Album(9L), args.viewerScope())
    }

    @Test
    fun `several scope args resolve album then collection then bucket`() {
        setNav()
        val all = open("viewer/local/42?bucketId=1&orgKind=trash&albumId=9")
        assertEquals(ViewerScope.Album(9L), all.viewerScope())

        val two = open("viewer/local/42?bucketId=1&orgKind=trash")
        assertEquals(ViewerScope.Collection(OrganizationKind.TRASH), two.viewerScope())

        val one = open("viewer/local/42?bucketId=1")
        assertEquals(ViewerScope.Bucket(1L), one.viewerScope())
    }

    @Test
    fun `the cloud route carries chat id and message id`() {
        setNav()
        val args = open(ViewerRoute.cloud(100L, 7L))

        assertEquals(ViewerRoute.CLOUD, currentRoute())
        assertEquals(100L, args.getLong(ViewerRoute.ARG_CHAT_ID))
        assertEquals(7L, args.getLong(ViewerRoute.ARG_MESSAGE_ID))
    }

    @Test
    fun `a folder album route matches its pattern and carries the decoded title`() {
        setNav()
        val args = open(AlbumRoute.create(77L, "Trip Photos"))

        assertEquals(AlbumRoute.PATTERN, currentRoute())
        assertEquals(77L, args.getLong(AlbumRoute.ARG_BUCKET_ID))
        // The header shows this string verbatim, so an encoded value would be a user-visible bug.
        assertEquals("Trip Photos", args.getString(AlbumRoute.ARG_TITLE))
    }

    @Test
    fun `a custom album route matches its pattern and carries the decoded name`() {
        setNav()
        val args = open(AlbumRoute.createCustom(9L, "Summer 2026"))

        assertEquals(AlbumRoute.CUSTOM_PATTERN, currentRoute())
        assertEquals(9L, args.getLong(AlbumRoute.ARG_ALBUM_ID))
        // Observed from the database rather than the route, but the initial header value still
        // must not show percent-escapes.
        assertEquals("Summer 2026", args.getString(AlbumRoute.ARG_NAME))
    }

    @Test
    fun `an organization route matches its pattern and parses the kind`() {
        setNav()
        val args = open(OrganizationRoute.create(OrganizationKind.FAVORITES))

        assertEquals(OrganizationRoute.PATTERN, currentRoute())
        assertEquals("favorites", args.getString(OrganizationRoute.ARG_KIND))
        assertEquals(OrganizationKind.FAVORITES, OrganizationRoute.kindOf(args.getString(OrganizationRoute.ARG_KIND)))
    }

    @Test
    fun `the organization kind parser is case-insensitive and rejects unknown values`() {
        assertEquals(OrganizationKind.FAVORITES, OrganizationRoute.kindOf("FAVORITES"))
        assertEquals(OrganizationKind.TRASH, OrganizationRoute.kindOf("trash"))
        assertNull(OrganizationRoute.kindOf("albums"))
        assertNull(OrganizationRoute.kindOf(null))
    }

    @Test
    fun `query args keep the named placeholder form the parser requires`() {
        assertTrue(
            ViewerRoute.LOCAL.contains(
                "?${ViewerRoute.ARG_BUCKET_ID}={${ViewerRoute.ARG_BUCKET_ID}}" +
                    "&${ViewerRoute.ARG_ORG_KIND}={${ViewerRoute.ARG_ORG_KIND}}" +
                    "&${ViewerRoute.ARG_ALBUM_ID}={${ViewerRoute.ARG_ALBUM_ID}}",
            ),
        )
        assertTrue(AlbumRoute.CUSTOM_PATTERN.contains("?${AlbumRoute.ARG_NAME}={${AlbumRoute.ARG_NAME}}"))
        // A bare `?{key}` (the single-query-param form) hands the whole raw query to every arg;
        // neither pattern may ever degrade to it.
        assertFalse(ViewerRoute.LOCAL.contains("?{"))
        assertFalse(AlbumRoute.CUSTOM_PATTERN.contains("?{"))
    }

    @Test
    fun `every viewer route lives under the prefix the app shell fades on`() {
        // TelepicApp fades the chrome when the current route starts with [ViewerRoute.PREFIX], so
        // the namespace is spelled once and every viewer route must sit under it — while no tab
        // route may, or the fade would fire on a top-level destination.
        val routes = listOf(
            ViewerRoute.LOCAL,
            ViewerRoute.CLOUD,
            ViewerRoute.local(1L),
            ViewerRoute.localInBucket(1L, 2L),
            ViewerRoute.localInCollection(1L, OrganizationKind.TRASH),
            ViewerRoute.localInCustomAlbum(1L, 2L),
            ViewerRoute.cloud(1L, 2L),
        )
        routes.forEach {
            assertTrue("route must start with ${ViewerRoute.PREFIX}: $it", it.startsWith(ViewerRoute.PREFIX))
        }
        TelepicDestination.entries.forEach {
            assertFalse("tab ${it.route} must not sit under the viewer prefix", it.route.startsWith(ViewerRoute.PREFIX))
        }
    }
}
