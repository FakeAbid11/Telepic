package com.telepix.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.media.MapLocationRepository
import com.telepix.domain.media.GeotaggedMedia
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the interactive map. It exposes geotagged items (from the EXIF cache) as map pins using the
 * pure [com.telepix.data.media.MapClusterer] — grouping is a rendering concern computed off the main
 * thread — and triggers a bounded incremental scan when the screen is opened (never at app startup).
 */
class MapViewModel(
    private val mapLocationRepository: MapLocationRepository,
) : ViewModel() {

    val pins: StateFlow<List<com.telepix.data.media.MapClusterer.MapPin>> =
        mapLocationRepository.locations
            .map { items -> com.telepix.data.media.MapClusterer.cluster(items, DEFAULT_PRECISION) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of distinct geotagged items, for an honest "N locations" affordance. */
    val locationCount: StateFlow<Int> =
        mapLocationRepository.locations.map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun refresh() {
        viewModelScope.launch { mapLocationRepository.rescan() }
    }

    private companion object {
        const val DEFAULT_PRECISION = 4 // ~11 m grid; recomputed by the view when zoomed.
    }
}
