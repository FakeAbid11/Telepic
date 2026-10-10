package com.telepic.ui.screens.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.media.AlbumRepository
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.Album
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One album's contents: the same paged, day-grouped [PhotosItem] stream and backup-status snapshot as
 * the main Photos timeline, scoped to a single MediaStore bucket. Reuses [AlbumRepository] so album
 * media behaves identically to the timeline (and never re-implements the grid).
 */
class AlbumContentsViewModel(
    val album: Album,
    albumRepository: AlbumRepository,
    backupStatusRepository: BackupStatusRepository,
) : ViewModel() {

    val media: Flow<PagingData<PhotosItem>> =
        albumRepository.mediaInBucket(album.bucketId).cachedIn(viewModelScope)

    val backupStates: StateFlow<Map<Long, MediaBackupVisualState>> =
        backupStatusRepository.visualStates
            .map { rows -> buildMap { rows.forEach { (id, s) -> id.toLongOrNull()?.let { put(it, s) } } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
