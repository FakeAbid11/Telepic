package com.telepix.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the primary navigation set is represented correctly: exactly the five PRD
 * destinations, each with a unique, non-blank route, and a stable start destination.
 */
class TelepixDestinationTest {

    @Test
    fun `contains exactly the five primary destinations`() {
        val expected = setOf("Photos", "Cloud", "Albums", "Map", "Settings")
        val actual = TelepixDestination.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `every destination has a unique non-blank route`() {
        val routes = TelepixDestination.entries.map { it.route }
        assertEquals("routes must be unique", routes.size, routes.distinct().size)
        assertTrue("routes must be non-blank", routes.all { it.isNotBlank() })
    }

    @Test
    fun `every destination declares a label resource`() {
        TelepixDestination.entries.forEach { destination ->
            assertTrue(destination.labelRes != 0)
        }
    }

    @Test
    fun `start destination is Photos`() {
        assertSame(TelepixDestination.Photos, TelepixDestination.Start)
    }

    @Test
    fun `fromRoute resolves known routes and falls back to start`() {
        TelepixDestination.entries.forEach { destination ->
            assertSame(destination, TelepixDestination.fromRoute(destination.route))
        }
        assertSame(TelepixDestination.Start, TelepixDestination.fromRoute("unknown"))
        assertSame(TelepixDestination.Start, TelepixDestination.fromRoute(null))
    }
}
