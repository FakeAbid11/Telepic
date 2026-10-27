package com.telepic.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The basemap choice, and the three things about it that can silently break the map.
 *
 * Plain JUnit, not Robolectric: unlike the raster tile sources this replaced, a style is just a URL,
 * so the whole contract is testable as strings.
 *
 * What is worth pinning here is not that the URLs are non-blank — it is that they are **keyless**.
 * The previous provider served these styles free until it started rejecting keyless requests, at
 * which point every request answered `200` with a watermarked tile. A `?key=` creeping back into
 * either constant would reintroduce a billable credential in a public repository; the assertion
 * below is what makes that a test failure instead of a support ticket.
 */
class BasemapStylesTest {

    private val urls = listOf(BasemapStyles.LIGHT_STYLE_URL, BasemapStyles.DARK_STYLE_URL)

    @Test
    fun `the dark theme gets the dark style and the light theme the light one`() {
        assertEquals(BasemapStyles.DARK_STYLE_URL, BasemapStyles.forDarkTheme(dark = true))
        assertEquals(BasemapStyles.LIGHT_STYLE_URL, BasemapStyles.forDarkTheme(dark = false))
    }

    @Test
    fun `the two styles are different documents`() {
        // Not cosmetic: MapLibre's ambient cache is keyed by URL, so two styles sharing a URL would
        // serve cached light tiles into a dark map (and the reverse), surviving restarts.
        assertNotEquals(BasemapStyles.LIGHT_STYLE_URL, BasemapStyles.DARK_STYLE_URL)
        urls.forEach { url ->
            assertTrue("a style URL must not be blank", url.isNotBlank())
            assertTrue("a style URL must be https, got $url", url.startsWith("https://"))
        }
    }

    @Test
    fun `neither style needs an api key`() {
        urls.forEach { url ->
            assertFalse("a style URL must not carry a key, got $url", url.contains("key="))
            assertFalse("a style URL must not carry a query string, got $url", url.contains("?"))
        }
    }

    @Test
    fun `the attribution credits every party OpenFreeMap requires`() {
        val notice = BasemapStyles.ATTRIBUTION
        // OpenFreeMap's terms name the host, the tile schema and the data — all three, not just OSM.
        assertTrue("must credit OpenFreeMap: $notice", notice.contains("OpenFreeMap"))
        assertTrue("must credit OpenMapTiles: $notice", notice.contains("OpenMapTiles"))
        assertTrue("must credit OpenStreetMap: $notice", notice.contains("OpenStreetMap"))
        // The replaced provider's credit must be gone, or the line names a party no longer involved.
        assertFalse("must not still credit CARTO: $notice", notice.contains("CARTO"))
    }

    @Test
    fun `the attribution links somewhere`() {
        assertTrue(
            "the attribution must be a reachable https link",
            BasemapStyles.ATTRIBUTION_URL.startsWith("https://"),
        )
    }

    @Test
    fun `the zoom range is the one the screen enforces`() {
        // MIN 0 is MapLibre's own floor; MAX 19 is what `setMaxZoomPreference` gets, and what
        // `MapCameraResolver` clamps a remembered zoom to. All three have to agree.
        assertEquals(0.0, BasemapStyles.MIN_ZOOM, 0.0)
        assertEquals(19.0, BasemapStyles.MAX_ZOOM, 0.0)
        assertTrue(BasemapStyles.MAX_ZOOM > BasemapStyles.MIN_ZOOM)
    }
}
