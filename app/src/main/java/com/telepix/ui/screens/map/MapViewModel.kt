package com.telepix.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.media.MapLocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the interactive map. It exposes geotagged items (from the EXIF cache) as map pins using the
 * pure [com.telepix.data.media.MapClusterer] — grouping is a rendering concern computed off the main
 * thread — and triggers a bounded incremental scan when the screen is opened (never at app startup).
 *
 * Archived/trashed media is hidden on the map too ([hiddenIds]), so the pins match the timeline,
 * albums and Viewer: a filed-away photo must not stay visible on the map while the Viewer refuses to
 * swipe onto it. [isScanning] lets the screen distinguish "still loading" from "genuinely no locations"
 * instead of flashing the empty state during the first scan.
 */
class MapViewModel(
    private val mapLocationRepository: MapLocationRepository,
    private val hiddenIds: kotlinx.coroutines.flow.Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Geotagged items minus anything the user archived or trashed. */
    private val visibleLocations =
        combine(mapLocationRepository.locations, hiddenIds) { items, hidden ->
            if (hidden.isEmpty()) items else items.filterNot { it.mediaId in hidden }
        }

    val pins: StateFlow<List<com.telepix.data.media.MapClusterer.MapPin>> =
        visibleLocations
            .map { items -> com.telepix.data.media.MapClusterer.cluster(items, DEFAULT_PRECISION) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of distinct visible geotagged items, for an honest "N locations" affordance. */
    val locationCount: StateFlow<Int> =
        visibleLocations.map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun refresh() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                mapLocationRepository.rescan()
            } finally {
                _isScanning.value = false
            }
        }
    }

    private companion object {
        const val DEFAULT_PRECISION = 4 // ~11 m grid; recomputed by the view when zoomed.
    }
}
