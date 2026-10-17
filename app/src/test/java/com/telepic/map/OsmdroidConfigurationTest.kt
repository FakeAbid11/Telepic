package com.telepic.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile User-Agent is load-bearing: OpenStreetMap rejects a request whose agent is blank or a
 * library default, which presents to the user as a permanently blank map. These pin the two ways
 * that could silently regress.
 */
class OsmdroidConfigurationTest {

    @Test
    fun `user agent identifies the app with its version and id`() {
        val ua = OsmdroidConfiguration.userAgent("0.1.0", "com.telepic")
        assertTrue(ua.contains("Telepic"))
        assertTrue(ua.contains("0.1.0"))
        assertTrue(ua.contains("com.telepic"))
    }

    @Test
    fun `user agent is never blank and never the osmdroid default`() {
        val ua = OsmdroidConfiguration.userAgent("", "")
        assertFalse(ua.isBlank())
        assertFalse(ua == "osmdroid")
        assertEquals(ua.trim(), ua)
    }
}
