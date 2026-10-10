package com.telepic.ui.screens.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.data.media.AlbumRepository
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.PhotosItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One album's contents: the same paged, day-grouped [PhotosItem] stream and backup-status snapshot as
 * the main Photos timeline, scoped to a single MediaStore bucket. Reuses [AlbumRepository] so album
 * media behaves identically to the timeline (and never re-implements the grid). Selection + bulk
 * backup mirror the Photos timeline exactly — same engine, same stable-id semantics.
 */
class AlbumContentsViewModel(
    bucketId: Long,
    val title: String,
    albumRepository: AlbumRepository,
    backupStatusRepository: BackupStatusRepository,
    private val backupCoordinator: BackupCoordinator? = null,
    organizationRepository: MediaOrganizationRepository? = null,
) : ViewModel() {

    val media: Flow<PagingData<PhotosItem>> =
        albumRepository.mediaInBucket(bucketId).cachedIn(viewModelScope)

    val backupStates: StateFlow<Map<Long, MediaBackupVisualState>> =
        backupStatusRepository.visualStates
            .map { rows -> buildMap { rows.forEach { (id, s) -> id.toLongOrNull()?.let { put(it, s) } } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Favorited ids for the tile badge — same batched snapshot as the Photos timeline. */
    val favoriteIds: StateFlow<Set<Long>> =
        (organizationRepository?.favoriteIds ?: flowOf(emptySet()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // --- Multi-selection: identical semantics to the Photos timeline (§15). --------------------
    // Keyed by stable MediaStore id (never grid position), insertion-ordered, updated synchronously
    // so the selected set is consistent with the UI on the same frame it changes.
    private val _selected = MutableStateFlow<Map<Long, LocalMedia>>(emptyMap())
    val selectedItems: StateFlow<Map<Long, LocalMedia>> = _selected.asStateFlow()

    /** Transient bulk-backup feedback for a snackbar; null when nothing to report. */
    private val _selectionMessage = MutableStateFlow<BulkBackupSummary?>(null)
    val selectionMessage: StateFlow<BulkBackupSummary?> = _selectionMessage.asStateFlow()

    /** Long-press entry: begin a selection containing this item (idempotent if already selecting). */
    fun beginSelection(media: LocalMedia) {
        _selected.update { if (it.containsKey(media.id)) it else LinkedHashMap(it).also { m -> m[media.id] = media } }
    }

    /** Tap while selecting: toggle one item without disturbing the rest of the selection. */
    fun toggleSelection(media: LocalMedia) {
        _selected.update { current ->
            val next = LinkedHashMap(current)
            if (next.remove(media.id) == null) next[media.id] = media
            next
        }
    }

    fun clearSelection() {
        _selected.value = emptyMap()
    }

    /**
     * Back up every selected photo through the existing engine (recognition + dedup + queue + schedule).
     * The coordinator recognizes already-backed-up items and skips them, so the count reflects genuine
     * new work, never a fabricated success.
     */
    fun backupSelected() {
        val coordinator = backupCoordinator ?: return
        val items = _selected.value.values.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _selectionMessage.value = coordinator.backupAll(items)
            clearSelection()
        }
    }

    fun consumeSelectionMessage() {
        _selectionMessage.value = null
    }
}
