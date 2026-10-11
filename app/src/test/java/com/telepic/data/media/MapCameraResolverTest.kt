package com.telepic.data.media

import com.telepic.domain.media.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The map's opening camera is pure policy, so it is pinned here: an explicit focus beats the
 * remembered position, which beats framing all the photos — and no input ever produces a fabricated
 * center.
 */
class MapCameraResolverTest {

    private val plan = MapCameraPlan.Plan(centerLat = 10.0, centerLon = 20.0, zoom = 7.0)

    @Test
    fun `an explicit focus wins over the remembered camera and the fit plan`() {
        val decision = MapCameraResolver.resolve(
            focus = GeoLocation(51.5, -0.12),
            saved = SavedCamera(1.0, 2.0, 5.0),
            plan = plan,
        )
        assertEquals(
            MapCameraResolver.Decision.Apply(51.5, -0.12, MapCameraResolver.FOCUS_ZOOM),
            decision,
        )
    }

    @Test
    fun `the remembered camera is used when there is no focus`() {
        val decision = MapCameraResolver.resolve(focus = null, saved = SavedCamera(3.0, 4.0, 9.0), plan = plan)
        assertEquals(MapCameraResolver.Decision.Apply(3.0, 4.0, 9.0), decision)
    }

    @Test
    fun `the fit plan is used when nothing is remembered`() {
        val decision = MapCameraResolver.resolve(focus = null, saved = null, plan = plan)
        assertEquals(MapCameraResolver.Decision.Apply(10.0, 20.0, 7.0), decision)
    }

    @Test
    fun `an empty map keeps the caller's neutral default instead of inventing a center`() {
        assertEquals(
            MapCameraResolver.Decision.KeepDefault,
            MapCameraResolver.resolve(focus = null, saved = null, plan = null),
        )
    }

    @Test
    fun `an invalid focus is ignored rather than trusted`() {
        val decision = MapCameraResolver.resolve(
            focus = GeoLocation(Double.NaN, 0.0),
            saved = SavedCamera(3.0, 4.0, 9.0),
            plan = null,
        )
        assertEquals(MapCameraResolver.Decision.Apply(3.0, 4.0, 9.0), decision)
    }

    @Test
    fun `an out-of-range remembered camera falls through instead of placing a bogus pin`() {
        val decision = MapCameraResolver.resolve(
            focus = null,
            saved = SavedCamera(120.0, 4.0, 9.0),
            plan = plan,
        )
        assertEquals(MapCameraResolver.Decision.Apply(10.0, 20.0, 7.0), decision)
    }

    @Test
    fun `a remembered zoom outside the osmdroid range is clamped`() {
        val decision = MapCameraResolver.resolve(focus = null, saved = SavedCamera(1.0, 2.0, 99.0), plan = null)
        assertEquals(MapCameraResolver.Decision.Apply(1.0, 2.0, 21.0), decision)
    }

    @Test
    fun `recenter frames all photos and ignores the remembered view`() {
        assertEquals(
            MapCameraResolver.Decision.Apply(10.0, 20.0, 7.0),
            MapCameraResolver.recenter(plan),
        )
    }

    @Test
    fun `recenter with no photos keeps the default`() {
        assertEquals(MapCameraResolver.Decision.KeepDefault, MapCameraResolver.recenter(null))
    }

    @Test
    fun `focus rejects a non-finite or out-of-range coordinate`() {
        assertEquals(MapCameraResolver.Decision.KeepDefault, MapCameraResolver.focus(Double.NaN, 0.0))
        assertEquals(MapCameraResolver.Decision.KeepDefault, MapCameraResolver.focus(0.0, 181.0))
        assertEquals(
            MapCameraResolver.Decision.Apply(1.0, 2.0, MapCameraResolver.FOCUS_ZOOM),
            MapCameraResolver.focus(1.0, 2.0),
        )
    }
}
