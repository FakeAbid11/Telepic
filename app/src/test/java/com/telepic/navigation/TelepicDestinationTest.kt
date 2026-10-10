package com.telepic.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the primary navigation set is represented correctly: exactly the five PRD
 * destinations, each with a unique, non-blank route, and a stable start destination.
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
}
