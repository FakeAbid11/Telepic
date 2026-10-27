package com.telepic.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The basemap selection, the keyed CARTO URL contract, and the cache names that keep stale tiles out.
 *
 * Robolectric rather than plain JUnit: constructing an `XYTileSource` touches osmdroid classes that
 * assume an Android runtime, and a test that cannot even build its subject is worse than no test.
 *
 * Tests never assert against [com.telepic.BuildConfig.CARTO_API_KEY]: on `main` CI that value is the
 * real secret, and a failing assertion message would print the keyed URL into a public Actions log.
 * Every URL assertion uses [TelepicTileSources.lightFor] / [darkFor] with a fake key instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelepicTileSourcesTest {

    private val tile = MapTileIndex.getTileIndex(5, 10, 20)
    private val lightPrefix = "https://basemaps.cartocdn.com/rastertiles/light_all/5/10/20.png"
    private val darkPrefix = "https://basemaps.cartocdn.com/rastertiles/dark_all/5/10/20.png"

    /** The URL a tile actually resolves to — the whole point of the source, and easy to get wrong. */
    private fun tileUrl(source: OnlineTileSourceBase): String = source.getTileURLString(tile)

    @Test
    fun `the dark theme gets the dark basemap and the light theme the light one`() {
        assertTrue(TelepicTileSources.forDarkTheme(true) === TelepicTileSources.Dark)
        assertTrue(TelepicTileSources.forDarkTheme(false) === TelepicTileSources.Light)
    }

    @Test
    fun `a keyed light tile is the rastertiles URL with the key as a query`() {
        assertEquals("$lightPrefix?key=test-key-123", tileUrl(TelepicTileSources.lightFor("test-key-123")))
    }

    @Test
    fun `a keyed dark tile is the rastertiles URL with the key as a query`() {
        assertEquals("$darkPrefix?key=test-key-123", tileUrl(TelepicTileSources.darkFor("test-key-123")))
    }

    @Test
    fun `a blank key resolves to the unkeyed URL`() {
        val url = tileUrl(TelepicTileSources.lightFor(""))
        assertEquals(lightPrefix, url)
        assertFalse("a blank key must not add a query", url.contains("key="))
    }

    @Test
    fun `a whitespace-only key is treated as blank`() {
        assertEquals(lightPrefix, tileUrl(TelepicTileSources.lightFor("  \n\t ")))
    }

    @Test
    fun `the key is trimmed before it is appended`() {
        assertEquals(
            "$lightPrefix?key=test-key-123",
            tileUrl(TelepicTileSources.lightFor("  test-key-123\n")),
        )
    }

    @Test
    fun `the key is URL-encoded`() {
        // URLEncoder's query-string rules: space becomes '+', '&' becomes %26.
        assertEquals("$lightPrefix?key=a+b%26c", tileUrl(TelepicTileSources.lightFor("a b&c")))
    }

    /**
     * Names are the cache key: `V2` is the keyed URL contract, `NoKey` keeps keyless builds from
     * poisoning a keyed build's cache with watermarked tiles. The names must also differ per style.
     */
    @Test
    fun `cache names encode the URL contract and whether a key is present`() {
        assertEquals("TelepicLightV2", TelepicTileSources.lightFor("test-key-123").name())
        assertEquals("TelepicDarkV2", TelepicTileSources.darkFor("test-key-123").name())
        assertEquals("TelepicLightV2NoKey", TelepicTileSources.lightFor("").name())
        assertEquals("TelepicDarkV2NoKey", TelepicTileSources.darkFor("").name())

        assertNotEquals(
            TelepicTileSources.lightFor("test-key-123").name(),
            TelepicTileSources.darkFor("test-key-123").name(),
        )
        assertFalse(TelepicTileSources.lightFor("test-key-123").name().contains("test-key-123"))
    }

    @Test
    fun `the cache path is derived from the tile-source name`() {
        val keyed = TelepicTileSources.lightFor("test-key-123")
        assertTrue(keyed.getTileRelativeFilenameString(tile).startsWith("TelepicLightV2/5/10/20"))

        val keyless = TelepicTileSources.lightFor("")
        assertTrue(keyless.getTileRelativeFilenameString(tile).startsWith("TelepicLightV2NoKey/5/10/20"))
    }

    @Test
    fun `the configured source uses the new rastertiles path`() {
        // No URL interpolation in the message: on main the ambient source carries the real key.
        assertTrue(tileUrl(TelepicTileSources.Light).contains("/rastertiles/light_all/"))
        assertTrue(tileUrl(TelepicTileSources.Dark).contains("/rastertiles/dark_all/"))
        assertTrue(tileUrl(TelepicTileSources.Light).startsWith("https://basemaps.cartocdn.com/"))
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
