package com.telepic.map

/**
 * The two basemaps the map can draw, and the choice between them.
 *
 * Telepic used **CARTO** raster tiles (`light_all` / `dark_all`). Those were free without a key until
 * 25 September 2026, when CARTO began rejecting keyless raster requests: the same URL now answers
 * with a `200` and a tile that literally reads "API KEY REQUIRED — carto.com/basemaps/apikey". A
 * watermark is worse than an error, because it is cached for 30 days.
 *
 * The requirement that replaced it was strict: **free, unlimited, no API key, and both a light and a
 * dark style.** No raster provider meets that. OSM's and Wikimedia's tile servers forbid application
 * use, and CARTO, Stadia, MapTiler and Thunderforest all need keys. The one service that does meet it
 * is **OpenFreeMap**: a public instance with no request limits, no registration, no user database and
 * no keys, funded by donations, with commercial use allowed and the whole stack open source.
 *
 * OpenFreeMap serves **vector** tiles, so this is also why the map engine is MapLibre GL rather than
 * osmdroid — osmdroid can only draw raster tiles, and the styles below are MapLibre style documents
 * (a TileJSON source plus layers), not image pyramids.
 *
 * The two styles are [LIGHT_STYLE_URL] and [DARK_STYLE_URL]. They come from the same cartography
 * family, so switching the theme changes the palette rather than replacing the map with a
 * differently drawn one — the same reasoning that kept both CARTO styles from one family.
 *
 * Attribution: OpenFreeMap requires OpenFreeMap, OpenMapTiles (the tile schema) and OpenStreetMap
 * (the data) to be credited. [ATTRIBUTION] is that string; the visible line and its link live in
 * `MapScreen` and `strings.xml`.
 */
object BasemapStyles {

    /** The light style: OpenFreeMap's Positron, a pale grey basemap built for data overlays. */
    const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

    /** The dark style: the same cartography, inverted for the dark theme. */
    const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

    /**
     * Required by OpenFreeMap's terms: the host, the tile schema and the data all have to be named.
     * Natural Earth's shaded relief, which the styles also draw, is public domain.
     */
    const val ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"

    /** Where the attribution line links: the project's own page, which repeats all three credits. */
    const val ATTRIBUTION_URL = "https://openfreemap.org"

    /**
     * The zoom range the screen allows.
     *
     * OpenFreeMap's vector source is published to z14 and MapLibre **overzooms** past it, so the map
     * still renders street-level detail at 19 without asking for tiles that do not exist. The minimum
     * is 0 rather than 1: the styles draw a complete world at z0, and a library spread across
     * continents should be able to show all of it.
     */
    const val MIN_ZOOM = 0.0
    const val MAX_ZOOM = 19.0

    /**
     * The basemap matching the app's current theme.
     *
     * Takes the app's own dark decision (see `LocalDarkTheme`), never `isSystemInDarkTheme()`: the
     * user can force Dark while the system is Light, and the map must agree with the rest of the UI
     * rather than with the device.
     */
    fun forDarkTheme(dark: Boolean): String = if (dark) DARK_STYLE_URL else LIGHT_STYLE_URL
}
