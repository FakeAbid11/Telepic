package com.telepic.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The basemap selection, and the one thing about it that can silently break the map.
 *
 * Robolectric rather than plain JUnit: constructing an `XYTileSource` touches osmdroid classes that
 * assume an Android runtime, and a test that cannot even build its subject is worse than no test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelepicTileSourcesTest {

    /** The URL a tile actually resolves to — the whole point of the source, and easy to get wrong. */
    private fun tileUrl(source: org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase): String =
        source.getTileURLString(MapTileIndex.getTileIndex(5, 10, 20))

    @Test
    fun `the dark theme gets the dark basemap and the light theme the light one`() {
        assertTrue(TelepicTileSources.forDarkTheme(true) === TelepicTileSources.Dark)
        assertTrue(TelepicTileSources.forDarkTheme(false) === TelepicTileSources.Light)
    }

    /**
     * The two names must differ: osmdroid keys its tile cache directory by tile-source name, so
     * sharing one would let a cached light tile be drawn into a dark map — a corruption that
     * survives restarts, because the cache does.
     */
    @Test
    fun `the two basemaps have distinct names so they get separate tile caches`() {
        // `name()` is a plain method on `ITileSource`, not a `getName()` getter, so it stays a call.
        assertNotEquals(TelepicTileSources.Light.name(), TelepicTileSources.Dark.name())
        assertTrue(TelepicTileSources.Light.name().isNotBlank())
        assertTrue(TelepicTileSources.Dark.name().isNotBlank())
    }

    @Test
    fun `each basemap resolves to its own CARTO style`() {
        val light = tileUrl(TelepicTileSources.Light)
        val dark = tileUrl(TelepicTileSources.Dark)

        assertTrue("light tiles must come from light_all, got $light", light.contains("light_all/"))
        assertTrue("dark tiles must come from dark_all, got $dark", dark.contains("dark_all/"))
        // The constructor's argument order is a trap: `XYTileSource` takes the filename extension
        // fifth and the copyright *last*, and its 6-arg overload leaves the copyright null. Getting
        // it wrong still compiles, still produces a plausible-looking source, and appends the notice
        // to every tile URL instead of `.png` — so the resolved URL is what gets asserted here.
        assertTrue("a tile URL must be https, got $light", light.startsWith("https://"))
        assertTrue("a tile URL must be a png, got $dark", dark.endsWith(".png"))
    }

    /** CARTO's terms require the data (OSM) *and* the rendering (CARTO) to be credited. */
    @Test
    fun `both basemaps credit OpenStreetMap and CARTO`() {
        listOf(TelepicTileSources.Light, TelepicTileSources.Dark).forEach { source ->
            val notice = source.copyrightNotice
            assertTrue("must credit OpenStreetMap: $notice", notice.contains("OpenStreetMap"))
            assertTrue("must credit CARTO: $notice", notice.contains("CARTO"))
        }
    }

    /** Matches `MapScreen`'s `MAX_ZOOM`, so the map cannot be panned past what the source serves. */
    @Test
    fun `both basemaps cover the zoom range the screen allows`() {
        listOf(TelepicTileSources.Light, TelepicTileSources.Dark).forEach { source ->
            assertEquals(0, source.minimumZoomLevel)
            assertEquals(19, source.maximumZoomLevel)
        }
    }
}
