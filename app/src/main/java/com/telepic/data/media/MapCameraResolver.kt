package com.telepic.data.media

import com.telepic.domain.media.GeoLocation

/** The camera position the map was last left at, so reopening it resumes where the user was. */
data class SavedCamera(val centerLat: Double, val centerLon: Double, val zoom: Double)

/**
 * Pure decision for where the photo map's camera should point when its surface is created.
 *
 * The priority order is deliberate: an explicit focus (another screen asked to show one photo) beats
 * the remembered position, which beats framing all the user's photos. Nothing is invented — when no
 * input is usable the caller keeps its own neutral default rather than a fabricated center. Kept free
 * of osmdroid so it is testable without a device.
 */
object MapCameraResolver {

    /** Zoom used when jumping to a single photo — close enough to see its surroundings. */
    const val FOCUS_ZOOM = 15.0

    /** osmdroid's own limits; a remembered zoom outside this range is clamped, never trusted blindly. */
    private const val MIN_ZOOM = 1.0
    private const val MAX_ZOOM = 21.0

    sealed interface Decision {
        data class Apply(val centerLat: Double, val centerLon: Double, val zoom: Double) : Decision

        data object KeepDefault : Decision
    }

    /** The camera to apply when the surface is first created. */
    fun resolve(focus: GeoLocation?, saved: SavedCamera?, plan: MapCameraPlan.Plan?): Decision =
        focusDecision(focus)
            ?: savedDecision(saved)
            ?: planDecision(plan)
            ?: Decision.KeepDefault

    /** The framing the user asks for explicitly ("show all my photos"), ignoring the remembered view. */
    fun recenter(plan: MapCameraPlan.Plan?): Decision = planDecision(plan) ?: Decision.KeepDefault

    /** The camera for one tapped pin or one requested coordinate, validated before it is trusted. */
    fun focus(latitude: Double, longitude: Double): Decision {
        if (!latitude.isFinite() || !longitude.isFinite()) return Decision.KeepDefault
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return Decision.KeepDefault
        return Decision.Apply(latitude, longitude, FOCUS_ZOOM)
    }

    private fun focusDecision(focus: GeoLocation?): Decision? =
        focus?.takeIf { it.isValid }?.let { Decision.Apply(it.latitude, it.longitude, FOCUS_ZOOM) }

    private fun savedDecision(saved: SavedCamera?): Decision? {
        if (saved == null) return null
        if (!saved.centerLat.isFinite() || !saved.centerLon.isFinite() || !saved.zoom.isFinite()) return null
        if (saved.centerLat !in -90.0..90.0 || saved.centerLon !in -180.0..180.0) return null
        return Decision.Apply(saved.centerLat, saved.centerLon, saved.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
    }

    private fun planDecision(plan: MapCameraPlan.Plan?): Decision? =
        plan?.takeIf { it.zoom.isFinite() }
            ?.let { Decision.Apply(it.centerLat, it.centerLon, it.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
}
