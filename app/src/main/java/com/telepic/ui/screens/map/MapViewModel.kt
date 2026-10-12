package com.telepic.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.media.MapLocationRepository
import com.telepic.data.media.SavedCamera
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.settings.MapCameraRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the interactive map. It exposes visible geotagged items (from the EXIF cache) raw: the
 * grouping into pins is a rendering concern recomputed by the view at the current zoom level with
 * the pure [com.telepic.data.media.MapClusterer] + [com.telepic.data.media.MapZoomPrecision] seam.
 * The ViewModel triggers a bounded incremental scan when the screen is opened (never at app startup).
 *
 * Archived/trashed media is hidden on the map too ([hiddenIds]), so the pins match the timeline,
 * albums and Viewer: a filed-away photo must not stay visible on the map while the Viewer refuses to
 * swipe onto it. [isScanning] lets the screen distinguish "still loading" from "genuinely no locations"
 * instead of flashing the empty state during the first scan.
 */
class MapViewModel(
    private val mapLocationRepository: MapLocationRepository,
    private val hiddenIds: Flow<Set<Long>> = flowOf(emptySet()),
    private val cameraRepository: MapCameraRepository? = null,
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanFailed = MutableStateFlow(false)

    /**
     * Whether the last scan failed. Surfaced so the screen can tell a failed scan apart from a
     * library that genuinely holds no geotagged media: both used to render the identical "no
     * locations" empty state, which told the user their photos carried no GPS data when the scan
     * had in fact thrown.
     */
    val scanFailed: StateFlow<Boolean> = _scanFailed.asStateFlow()

    /** Geotagged items minus anything the user archived or trashed. */
    private val visibleLocations =
        combine(mapLocationRepository.locations, hiddenIds) { items, hidden ->
            if (hidden.isEmpty()) items else items.filterNot { it.mediaId in hidden }
        }

    /** Shared raw list for clustering *and* the count — one collection of the Room flow, one filter. */
    val locations: StateFlow<List<GeotaggedMedia>> =
        visibleLocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of distinct visible geotagged items, for an honest "N locations" affordance. */
    val locationCount: StateFlow<Int> =
        locations.map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _savedCamera = MutableStateFlow<SavedCamera?>(null)

    /** Where the map was last left, or null when nothing has been remembered yet. */
    val savedCamera: StateFlow<SavedCamera?> = _savedCamera.asStateFlow()

    private val _cameraResolved = MutableStateFlow(cameraRepository == null)

    /**
     * Whether the remembered camera has been read. The screen waits for this before creating the map
     * surface so it opens already framed, instead of visibly re-animating away from the default view.
     * Resolves immediately when no camera repository is supplied.
     */
    val cameraResolved: StateFlow<Boolean> = _cameraResolved.asStateFlow()

    init {
        val repository = cameraRepository
        if (repository != null) {
            viewModelScope.launch {
                _savedCamera.value = try {
                    repository.camera.first()
                } catch (_: Exception) {
                    null
                }
                _cameraResolved.value = true
            }
        }
    }

    /**
     * Remembers where the map was left. The write runs on [viewModelScope] rather than the screen's
     * composition scope: leaving the map cancels a composition scope immediately, which would drop
     * the final position — the one that matters most.
     *
     * The in-memory [savedCamera] is published only *after* the write lands. It is what the screen
     * reads as "where the map was left", so it may only ever assert a position the repository
     * actually holds — publishing before the write is what silently lost a failed save while the app
     * behaved as if it had been remembered.
     */
    fun rememberCamera(camera: SavedCamera) {
        val repository = cameraRepository
        if (repository == null) {
            // Nothing to persist; the in-memory value is the best available answer.
            _savedCamera.value = camera
            return
        }
        viewModelScope.launch {
            try {
                repository.save(camera)
                _savedCamera.value = camera
            } catch (_: Exception) {
                // Losing a remembered camera is not worth surfacing; the map still opens correctly —
                // at the last position that was actually persisted, which is the honest fallback.
            }
        }
    }

    /**
     * Bounded rescan of the EXIF location cache. Single-flight: a second call while one is running
     * is ignored, so concurrent refreshes can never race [_isScanning] (the first finisher used to
     * clear the flag while a second scan was still going, flashing the empty state). A scan failure
     * — e.g. permission revoked mid-run — is contained here instead of escaping into the
     * ViewModelScope and crashing the app; the already-known locations stay on screen.
     */
    fun refresh() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                mapLocationRepository.rescan()
                _scanFailed.value = false
            } catch (cancellation: CancellationException) {
                // A cancelled scan is not a failure: rethrown so it neither reports an error nor
                // counts as one, the same rule the rest of the app follows.
                throw cancellation
            } catch (_: Exception) {
                // Recorded rather than swallowed. The map still keeps whatever it already has — the
                // empty state is not a lie either — but the failure is now reportable instead of
                // being indistinguishable from an empty library.
                _scanFailed.value = true
            } finally {
                _isScanning.value = false
            }
        }
    }
}
