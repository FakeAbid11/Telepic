package com.telepic.map

import android.content.Context
import com.telepic.BuildConfig
import java.io.File
import org.osmdroid.config.Configuration

/**
 * One-time osmdroid setup, applied at application start rather than per-map.
 *
 * Configuring inside a `MapView`'s factory looked equivalent but was not: `Configuration.load()`
 * resets the User-Agent from preferences on every call, so the `isNullOrBlank()` guard that followed
 * it never ran and the UA was permanently the bare package name. [initialize] therefore sets the UA
 * *after* `load()` and does it once, so nothing can reset it later.
 */
object OsmdroidConfiguration {

    /** Only used to round-trip osmdroid's own keys (base path, cache path, debug flags). */
    private const val PREFS_NAME = "telepic_osmdroid"

    fun initialize(context: Context) {
        val config = Configuration.getInstance()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        config.load(context, prefs)
        // Must come after load(): load() overwrites the UA from prefs on every call.
        config.userAgentValue = userAgent(BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID)

        // An app-private cache under filesDir: always writable and permission-free on every supported
        // API level, unlike the external-storage default that can be missing or read-only.
        val basePath = File(context.filesDir, "osmdroid").apply { mkdirs() }
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = File(basePath, "tiles").apply { mkdirs() }

        // Debug builds only: osmdroid then logs each tile URL and its HTTP result under the "OsmDroid"
        // tag, which is what makes a blank map diagnosable (`adb logcat -s OsmDroid:V`).
        if (BuildConfig.DEBUG) {
            config.isDebugMode = true
            config.isDebugMapTileDownloader = true
        }
    }

    /**
     * The User-Agent sent with every tile request. OpenStreetMap's tile policy requires an agent that
     * identifies the application (never a library default), so this carries the app name, its version
     * and a project URL. Pure and unit-tested: an accidental blank or bare `"osmdroid"` would make the
     * tile server reject every request.
     */
    fun userAgent(versionName: String, applicationId: String): String =
        "Telepic/$versionName ($applicationId; +https://github.com/FakeAbid11/Telepic)"
}
