package com.telepic.map

import com.telepic.BuildConfig
import com.telepic.diagnostics.TelepicLog
import java.net.URLEncoder
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource

private const val MIN_ZOOM = 0
private const val MAX_ZOOM = 19
private const val TILE_SIZE_PX = 256
private const val TILE_EXTENSION = ".png"

/**
 * The two raster basemaps the map can draw, and the choice between them.
 *
 * Telepic used `TileSourceFactory.MAPNIK` — a single, fixed **light** style — which is why the map
 * ignored the app theme and why three separate workarounds existed around it (a near-white FAB, a
 * near-white scanning chip, and marker colours chosen to read on pale tiles). osmdroid ships no dark
 * tile source at all: `TileSourceFactory` offers MAPNIK, WIKIMEDIA, PUBLIC_TRANSPORT, the CLOUDMADE
 * pair, USGS, OpenTopo and the Chartbundle/NL overlays, none of which is a dark style. A custom source
 * is therefore the only way to follow the theme.
 *
 * Both styles come from **CARTO**, deliberately: one cartography family at two brightnesses means
 * toggling the theme changes the palette rather than replacing the map with a differently-drawn one.
 * The alternative — CARTO dark beside OSM Mapnik light — would give the two modes different label
 * styles, different colour keys and different feature density, which reads as two unrelated maps.
 *
 * ## CARTO's API key (since 2026-09-25)
 *
 * CARTO stopped serving raster tiles without a key; an unkeyed request is answered with an
 * "API KEY REQUIRED" watermark. The key is free (https://carto.com/basemaps/apikey/) and arrives by
 * email. CI injects it as [BuildConfig.CARTO_API_KEY] from the `CARTO_API_KEY` secret; it is never
 * committed. A blank key still builds, but every tile is watermarked and one warning is logged. The
 * release gate in `app/build.gradle.kts` refuses a release without a key.
 *
 * ## Attribution
 *
 * CARTO's terms require OpenStreetMap (the data) *and* CARTO (the rendering) to be credited. That is
 * what [COPYRIGHT] carries; the visible string and its link live in `MapScreen` and `strings.xml`.
 *
 * ## Cache names are load-bearing
 *
 * osmdroid keys its tile cache directory by tile-source name. The names below include `V2` (the
 * keyed URL contract) and `NoKey` (a build without the key), so a keyed build never serves tiles that
 * an earlier keyless build cached — including the watermarked tiles cached before this fix. Sharing a
 * name between light and dark would likewise let one style's tiles leak into the other. Any future
 * change to the tile URL contract must bump `V2`.
 */
object TelepicTileSources {

    /** CARTO's keyed raster endpoints. The trailing slash is part of the base: osmdroid appends z/x/y. */
    private const val LIGHT_BASE_URL = "https://basemaps.cartocdn.com/rastertiles/light_all/"
    private const val DARK_BASE_URL = "https://basemaps.cartocdn.com/rastertiles/dark_all/"

    /** Required by both providers: the data is OpenStreetMap's, the tiles are CARTO's. */
    const val COPYRIGHT = "© OpenStreetMap contributors © CARTO"

    init {
        if (BuildConfig.CARTO_API_KEY.isBlank()) {
            TelepicLog.warn(
                "MapTiles",
                "CARTO_API_KEY is blank: CARTO will watermark every tile. Set the CARTO_API_KEY " +
                    "secret (README, Configuration) and rebuild.",
            )
        }
    }

    val Light: OnlineTileSourceBase = carto(style = "Light", baseUrl = LIGHT_BASE_URL)
    val Dark: OnlineTileSourceBase = carto(style = "Dark", baseUrl = DARK_BASE_URL)

    /**
     * The basemap matching the app's current theme.
     *
     * Takes the app's own dark decision (see `LocalDarkTheme`), never `isSystemInDarkTheme()`: the
     * user can force Dark while the system is Light, and the map must agree with the rest of the UI
     * rather than with the device.
     */
    fun forDarkTheme(dark: Boolean): OnlineTileSourceBase = if (dark) Dark else Light

    /** Internal seams so tests can pin keyed and keyless URLs without depending on the build's key. */
    internal fun lightFor(apiKey: String): OnlineTileSourceBase =
        carto(style = "Light", baseUrl = LIGHT_BASE_URL, apiKey = apiKey)

    internal fun darkFor(apiKey: String): OnlineTileSourceBase =
        carto(style = "Dark", baseUrl = DARK_BASE_URL, apiKey = apiKey)

    private fun carto(
        style: String,
        baseUrl: String,
        apiKey: String = BuildConfig.CARTO_API_KEY,
    ): OnlineTileSourceBase = CartoTileSource(
        // The keyed and keyless builds get distinct cache directories: the first keyed build must not
        // reuse a watermarked tile an earlier keyless build cached under the same V2 schema.
        name = cacheName(style, apiKey),
        baseUrl = baseUrl,
        apiKey = apiKey,
    )

    private fun cacheName(style: String, apiKey: String): String =
        "Telepic${style}V2".let { if (apiKey.isBlank()) "${it}NoKey" else it }

    /**
     * `XYTileSource` renders `base + z/x/y + .png` and exposes no hook for a query string, so this
     * override is the mechanism. Encoding happens once in the constructor because the method runs per
     * tile on osmdroid's loader threads.
     */
    private class CartoTileSource(
        name: String,
        baseUrl: String,
        apiKey: String,
    ) : XYTileSource(
        name,
        MIN_ZOOM,
        MAX_ZOOM,
        TILE_SIZE_PX,
        TILE_EXTENSION,
        arrayOf(baseUrl),
        COPYRIGHT,
    ) {
        private val keyQuery: String = apiKey.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "?key=" + URLEncoder.encode(it, "UTF-8") }
            .orEmpty()

        override fun getTileURLString(pMapTileIndex: Long): String =
            super.getTileURLString(pMapTileIndex) + keyQuery
    }
}
