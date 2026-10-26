package com.telepic.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource

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
 * Attribution: CARTO's basemaps are free to use with credit, and they require OpenStreetMap (the data)
 * *and* CARTO (the rendering) to be credited. That is what [COPYRIGHT] carries; the visible string and
 * its link live in `MapScreen` and `strings.xml`.
 */
object TelepicTileSources {

    /** Matches `MapScreen`'s `MAX_ZOOM`; CARTO publishes `*_all` up to z20, but 19 is what the UI allows. */
    private const val MIN_ZOOM = 0
    private const val MAX_ZOOM = 19

    /** CARTO serves 256px raster tiles. */
    private const val TILE_SIZE_PX = 256

    private const val BASE_URL = "https://basemaps.cartocdn.com/"

    /** Required by both providers: the data is OpenStreetMap's, the tiles are CARTO's. */
    const val COPYRIGHT = "© OpenStreetMap contributors © CARTO"

    /**
     * The light basemap, for the light theme.
     *
     * The name is **load-bearing**, not cosmetic: osmdroid keys its tile cache directory by
     * tile-source name, so two distinct names give the two styles separate on-disk caches. Sharing a
     * name would let a cached light tile be served into a dark map (and the reverse) — a corruption
     * that survives restarts, because the cache does.
     */
    val Light: OnlineTileSourceBase = XYTileSource(
        "TelepicLight",
        MIN_ZOOM,
        MAX_ZOOM,
        TILE_SIZE_PX,
        "${BASE_URL}light_all/",
        COPYRIGHT,
    )

    /** The dark basemap, for the dark theme. See [Light] for why the name must differ. */
    val Dark: OnlineTileSourceBase = XYTileSource(
        "TelepicDark",
        MIN_ZOOM,
        MAX_ZOOM,
        TILE_SIZE_PX,
        "${BASE_URL}dark_all/",
        COPYRIGHT,
    )

    /**
     * The basemap matching the app's current theme.
     *
     * Takes the app's own dark decision (see `LocalDarkTheme`), never `isSystemInDarkTheme()`: the
     * user can force Dark while the system is Light, and the map must agree with the rest of the UI
     * rather than with the device.
     */
    fun forDarkTheme(dark: Boolean): OnlineTileSourceBase = if (dark) Dark else Light
}
