package com.telepic.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the primary navigation set is represented correctly: exactly the five PRD destinations,
 * each with a unique, non-blank route and a label, and a stable start destination — plus the
 * destination/tab split.
 *
 * Both halves of that split matter. Settings must stay in [TelepicDestination.entries], because its
 * route is still a real destination the nav graph declares and `navigateToTopLevel` navigates to; and
 * it must stay out of [TelepicDestination.tabs], because the bottom bar is for the places you browse
 * and stay in. Dropping it from the enum would break the route; leaving it in `tabs` would put it back
 * in the bar.
 */
class TelepicDestinationTest {

    @Test
    fun `contains exactly the five primary destinations`() {
        val expected = setOf("Photos", "Cloud", "Albums", "Map", "Settings")
        val actual = TelepicDestination.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `every destination has a unique non-blank route`() {
        val routes = TelepicDestination.entries.map { it.route }
        assertEquals("routes must be unique", routes.size, routes.distinct().size)
        assertTrue("routes must be non-blank", routes.all { it.isNotBlank() })
    }

    @Test
    fun `every destination declares a label resource`() {
        TelepicDestination.entries.forEach { destination ->
            assertTrue(destination.labelRes != 0)
        }
    }

    @Test
    fun `start destination is Photos`() {
        assertSame(TelepicDestination.Photos, TelepicDestination.Start)
    }

    @Test
    fun `fromRoute resolves known routes and falls back to start`() {
        TelepicDestination.entries.forEach { destination ->
            assertSame(destination, TelepicDestination.fromRoute(destination.route))
        }
        assertSame(TelepicDestination.Start, TelepicDestination.fromRoute("unknown"))
        assertSame(TelepicDestination.Start, TelepicDestination.fromRoute(null))
    }

    // --- the destination / tab split -----------------------------------------------------------

    @Test
    fun `the bottom bar shows every destination except Settings, in declaration order`() {
        assertEquals(
            listOf(
                TelepicDestination.Photos,
                TelepicDestination.Cloud,
                TelepicDestination.Albums,
                TelepicDestination.Map,
            ),
            TelepicDestination.tabs,
        )
    }

    @Test
    fun `Settings is navigable by route but is not a tab`() {
        val settings = TelepicDestination.Settings
        // Still a real destination: the nav graph declares its route and the gear navigates to it.
        assertSame(settings, TelepicDestination.fromRoute(settings.route))
        assertFalse(TelepicDestination.tabs.contains(settings))
    }

    @Test
    fun `every tab reports itself as a tab, and Settings reports none`() {
        TelepicDestination.tabs.forEach { tab ->
            assertSame(tab, TelepicDestination.topLevelOf(tab.route))
        }
        // Null is what hides the bottom bar. Settings must not sit there with no tab highlighted.
        assertNull(TelepicDestination.topLevelOf(TelepicDestination.Settings.route))
    }

    @Test
    fun `a non-tab route belongs to no tab`() {
        assertNull(TelepicDestination.topLevelOf("viewer/local/1"))
        assertNull(TelepicDestination.topLevelOf("albums/contents"))
        assertNull(TelepicDestination.topLevelOf(null))
    }
}
