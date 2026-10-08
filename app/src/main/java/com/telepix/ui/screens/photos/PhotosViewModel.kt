package com.telepix.ui.screens.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.domain.media.PhotosItem
import com.telepix.permissions.MediaPermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns Photos screen state: permission-aware loading, the paged media stream, refresh, and the
 * lifecycle-safe media change watcher. No MediaStore/ContentResolver work lives in the UI.
 */
class PhotosViewModel(
    private val repository: LocalMediaRepository,
    private val changeWatcher: MediaChangeWatcher,
) : ViewModel() {

    private val _permissionState = MutableStateFlow(MediaPermissionState.NotRequested)
    val permissionState: StateFlow<MediaPermissionState> = _permissionState.asStateFlow()

    /** Paged day-headers + media cells. Cached so rotation/recomposition don't re-query. */
    val media: Flow<PagingData<PhotosItem>> = repository.media.cachedIn(viewModelScope)

    init {
        // Re-read the library when MediaStore changes while the screen is alive.
        changeWatcher.start { repository.refresh() }
    }

    fun updatePermission(state: MediaPermissionState) {
        _permissionState.value = state
    }

    fun refresh() {
        repository.refresh()
    }

    override fun onCleared() {
        changeWatcher.stop()
        super.onCleared()
    }
}
