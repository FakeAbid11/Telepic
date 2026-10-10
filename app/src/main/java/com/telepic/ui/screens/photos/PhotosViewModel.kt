package com.telepic.ui.screens.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.media.LocalMediaRepository
import com.telepic.data.media.MediaChangeWatcher
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.PhotosItem
import com.telepic.permissions.MediaPermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns Photos screen state: permission-aware loading, the paged media stream, backup status (from
 * the batched [BackupStatusRepository] snapshot — never a per-tile Room query), refresh, and the
 * lifecycle-safe media change watcher. No MediaStore/ContentResolver/Room/hash work lives in the UI,
 * and the UI never infers backup success itself: `BACKED_UP` here means the backup layer already
 * confirmed remote success.
 *
 * Phase 10: when an [MediaOrganizationRepository] is wired, archived/trashed items are excluded at
 * the MediaStore query itself, and the favorite set is surfaced as one batched snapshot for tile
 * badges — so favorites are independent of, and never alter, backup state.
 */
class PhotosViewModel(
    private val repository: LocalMediaRepository,
    private val changeWatcher: MediaChangeWatcher,
    backupStatusRepository: BackupStatusRepository? = null,
    organizationRepository: MediaOrganizationRepository? = null,
    private val backupCoordinator: BackupCoordinator? = null,
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

    /** Favorited ids for the tile badge — one batched snapshot, no per-tile query. */
    val favoriteIds: StateFlow<Set<Long>> =
        (organizationRepository?.favoriteIds ?: kotlinx.coroutines.flow.flowOf(emptySet()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // --- Multi-selection (§15) -----------------------------------------------------------------
    //
    // Selection is keyed by the stable MediaStore id (never grid position), so it survives paging,
    // scrolling and recomposition; the LocalMedia captured at selection time is what gets enqueued,
    // so an item scrolled out of the loaded window stays selected. Ordering is insertion order. It is
    // a plain StateFlow (updated synchronously) rather than a derived stateIn, so the selected set is
    // always consistent with the UI on the same frame it changes.
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
     * Reports how many were newly enqueued — already-backed-up/pending items are recognised and skipped
     * by the coordinator, so the count reflects genuine new work, never a fabricated success.
     */
    fun backupSelected() {
        val coordinator = backupCoordinator ?: return
        val items = _selected.value.values.toList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            // The engine performs recognition/dedup and reports how many were genuinely new vs already
            // covered — the count reflects real new work, never a fabricated "all uploaded".
            _selectionMessage.value = coordinator.backupAll(items)
            clearSelection()
        }
    }

    fun consumeSelectionMessage() {
        _selectionMessage.value = null
    }

    init {
        // Re-read the library when MediaStore changes while the screen is alive.
        changeWatcher.start { repository.refresh() }
        // When organization state changes (archive/trash), re-read so the timeline hides/shows items.
        if (organizationRepository != null) {
            viewModelScope.launch {
                combine(organizationRepository.archivedIds, organizationRepository.trashedIds) { a, t -> a + t }
                    .collect { repository.refresh() }
            }
        }
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
