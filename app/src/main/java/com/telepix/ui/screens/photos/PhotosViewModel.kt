package com.telepix.ui.screens.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepix.data.backup.BackupStatusRepository
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.domain.backup.MediaBackupVisualState
import com.telepix.domain.media.PhotosItem
import com.telepix.permissions.MediaPermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Owns Photos screen state: permission-aware loading, the paged media stream, backup status (from
 * the batched [BackupStatusRepository] snapshot — never a per-tile Room query), refresh, and the
 * lifecycle-safe media change watcher. No MediaStore/ContentResolver/Room/hash work lives in the UI,
 * and the UI never infers backup success itself: `BACKED_UP` here means the backup layer already
 * confirmed remote success.
 */
class PhotosViewModel(
    private val repository: LocalMediaRepository,
    private val changeWatcher: MediaChangeWatcher,
    backupStatusRepository: BackupStatusRepository? = null,
) : ViewModel() {

    private val _permissionState = MutableStateFlow(MediaPermissionState.NotRequested)
    val permissionState: StateFlow<MediaPermissionState> = _permissionState.asStateFlow()

    /** Paged day-headers + media cells. Cached so rotation/recomposition don't re-query. */
    val media: Flow<PagingData<PhotosItem>> = repository.media.cachedIn(viewModelScope)

    /**
     * Backup status keyed by the local MediaStore id — the UI navigation identity (never filename
     * or hash). Tiles read `backupStates[id] ?: NONE`; the map comes from one batched queue flow.
     */
    val backupStates: StateFlow<Map<Long, MediaBackupVisualState>> =
        (backupStatusRepository?.visualStates ?: kotlinx.coroutines.flow.flowOf(emptyMap()))
            .map { rows ->
                buildMap(rows.size) {
                    rows.forEach { (id, state) -> id.toLongOrNull()?.let { put(it, state) } }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
