package com.telepic.map

import android.content.Context
import org.maplibre.android.MapLibre

/**
 * The one-time MapLibre setup, applied lazily rather than at application start.
 *
 * **Why not in `TelepicApplication.onCreate`.** Every Robolectric test in this project boots the real
 * `Application`, so anything done there runs ~85 times per test run. Constructing a `MapView` loads
 * MapLibre's native library, which cannot exist in a JVM test process; keeping initialisation out of
 * the Application is what lets the suite keep running on the JVM at all. The map is only needed when
 * the map surface is actually built, so that is where this runs — and the tests never build one.
 *
 * **Why it must still be idempotent and eager relative to the view.** `MapView.initialize` throws
 * `MapLibreConfigurationException` if `MapLibre.hasInstance()` is false, so this has to complete
 * before the first `MapView` is constructed. A `MapView` is only ever created from the map screen's
 * `AndroidView` factory, which calls this first.
 *
 * The instance holds no `Context` of its own: only the flag below, so nothing here can leak the
 * Activity that happened to open the map.
 */
object MapLibreConfiguration {

    @Volatile
    private var initialized = false

    /** Must run on the UI thread, before the first `MapView`. Safe to call repeatedly. */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // The application context, not the caller's: MapLibre keeps a reference to it for the
            // lifetime of the process.
            MapLibre.getInstance(context.applicationContext)
            initialized = true
        }
    }
}
