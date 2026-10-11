package com.telepic.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single coordinate another screen wants the photo map to show. */
data class MapFocus(val latitude: Double, val longitude: Double)

/**
 * One-shot handoff for "show this photo on the map".
 *
 * Deliberately held in memory rather than passed as a navigation argument: the Map route is a
 * top-level tab whose route string is matched *exactly* by [TelepicDestination.topLevelOf] to decide
 * tab highlighting and bottom-bar visibility, so appending optional args to it would silently hide
 * the navigation bar on the Map tab. A transient request keeps that contract untouched.
 *
 * It does not survive process death, which is correct for an action the user has just performed.
 */
class MapFocusRequest {

    private val _pending = MutableStateFlow<MapFocus?>(null)

    val pending: StateFlow<MapFocus?> = _pending.asStateFlow()

    fun request(latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        _pending.value = MapFocus(latitude, longitude)
    }

    /** Called by the map once it has applied the request, so it is never replayed. */
    fun consume() {
        _pending.value = null
    }
}
