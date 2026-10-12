package com.telepic.ui.screens.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.data.media.LocalMediaRepository
import com.telepic.data.media.MediaChangeWatcher
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.CustomAlbum
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.PhotosItem
import com.telepic.ui.components.BulkBackupReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    private val customAlbumRepository: CustomAlbumRepository? = null,
) : ViewModel() {

    /** Paged day-headers + media cells. Cached so rotation/recomposition don't re-query. */
    val media: Flow<PagingData<PhotosItem>> = repository.media.cachedIn(viewModelScope)

    /**
     * Backup status keyed by the local MediaStore id — the UI navigation identity (never filename
     * or hash). Tiles read `backupStates[id] ?: NONE`; the map comes from one batched queue flow.
     */
    val backupStates: StateFlow<Map<Long, MediaBackupVisualState>> =
        (backupStatusRepository?.visualStates ?: flowOf(emptyMap()))
            .map { rows ->
                buildMap(rows.size) {
                    rows.forEach { (id, state) -> id.toLongOrNull()?.let { put(it, state) } }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Favorited ids for the tile badge — one batched snapshot, no per-tile query. */
    val favoriteIds: StateFlow<Set<Long>> =
        (organizationRepository?.favoriteIds ?: flowOf(emptySet()))
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

    /** Transient bulk-backup feedback for the message bar; null when nothing to report. The report
     * carries a unique id so an identical summary still re-announces instead of being conflated. */
    private val _selectionReport = MutableStateFlow<BulkBackupReport?>(null)
    val selectionReport: StateFlow<BulkBackupReport?> = _selectionReport.asStateFlow()

    /** True while [backupSelected] is hashing/enqueueing — the action bar shows a spinner. */
    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

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
     * by the coordinator, so the count reflects genuine new work, never a fabricated success. Re-entry
     * while a run is in flight is ignored, and the busy flag gives the UI a real in-flight state.
     */
    fun backupSelected() {
        val coordinator = backupCoordinator ?: return
        val items = _selected.value.values.toList()
        if (items.isEmpty()) return
        if (_isBackingUp.value) return
        _isBackingUp.value = true
        viewModelScope.launch {
            try {
                // The engine performs recognition/dedup and reports how many were genuinely new vs already
                // covered — the count reflects real new work, never a fabricated "all uploaded".
                val summary = coordinator.backupAll(items)
                _selectionReport.value = BulkBackupReport(summary, System.nanoTime())
                clearSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // No report and no clearSelection: nothing is claimed about work that did not happen,
                // and the selection survives so the user can simply tap again. An escaping exception
                // here would be an uncaught main-thread exception — the app closing on a tap.
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun consumeSelectionMessage() {
        _selectionReport.value = null
    }

    // --- Adding the selection to a user-created album --------------------------------------------

    /** The user's own albums, for the "Add to album" picker. Empty when the feature is not wired. */
    val customAlbums: StateFlow<List<CustomAlbum>> =
        (customAlbumRepository?.albums ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * True while an add-to-album write is in flight, so the picker cannot start a second one.
     *
     * Distinct from [isBackingUp]: the two actions are independent, and sharing one flag would disable
     * "Back up" while an album write ran and vice versa for no reason the user could see.
     */
    private val _isAddingToAlbum = MutableStateFlow(false)
    val isAddingToAlbum: StateFlow<Boolean> = _isAddingToAlbum.asStateFlow()

    /**
     * Adds the current selection to [albumId].
     *
     * The selection is only cleared once the write has returned, and the grid is never told the photos
     * are "in the album" — the album screen reads membership from Room, so a write that did not land
     * simply produces an album that does not contain them.
     */
    fun addSelectionToAlbum(albumId: Long) {
        val repository = customAlbumRepository ?: return
        val ids = _selected.value.keys.toList()
        if (ids.isEmpty()) return
        if (_isAddingToAlbum.value) return
        _isAddingToAlbum.value = true
        viewModelScope.launch {
            try {
                repository.addMedia(albumId, ids)
                clearSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The selection survives so the user can retry; nothing is claimed about the album.
            } finally {
                _isAddingToAlbum.value = false
            }
        }
    }

    /**
     * Creates an album and puts the current selection in it, in that order.
     *
     * Used by the picker's "New album" entry. If the album is created but the add fails, the album
     * still exists and is simply empty — which is the honest outcome, and better than rolling the
     * album back and losing the name the user just typed.
     */
    fun createAlbumAndAddSelection(name: String) {
        val repository = customAlbumRepository ?: return
        val ids = _selected.value.keys.toList()
        if (_isAddingToAlbum.value) return
        _isAddingToAlbum.value = true
        viewModelScope.launch {
            try {
                val albumId = repository.create(name)
                if (albumId != null && ids.isNotEmpty()) repository.addMedia(albumId, ids)
                clearSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // See addSelectionToAlbum: the album list is the report.
            } finally {
                _isAddingToAlbum.value = false
            }
        }
    }

    init {
        // Re-read the library when MediaStore changes while the screen is alive.
        changeWatcher.start {
            repository.refresh()
            scheduleChangeSync()
        }
        // When organization state changes (archive/trash), re-read so the timeline hides/shows items.
        if (organizationRepository != null) {
            viewModelScope.launch {
                combine(organizationRepository.archivedIds, organizationRepository.trashedIds) { a, t -> a + t }
                    .collect { repository.refresh() }
            }
        }
    }

    /**
     * Debounced auto-backup trigger for foreground MediaStore changes: ContentObserver events fire
     * in bursts (a camera save is several callbacks), so the last tick within the window collapses
     * the burst into one preference-gated sync. Recognition dedups everything already covered, and
     * the scheduler's KEEP policy coalesces duplicate runs. Background coverage is the periodic
     * worker's job — this only keeps an open app fresh.
     */
    private var changeSyncJob: Job? = null

    private fun scheduleChangeSync() {
        val coordinator = backupCoordinator ?: return
        changeSyncJob?.cancel()
        changeSyncJob = viewModelScope.launch {
            delay(MEDIA_CHANGE_SYNC_DEBOUNCE_MS)
            try {
                coordinator.syncFromPreference()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A failed automatic pass leaves the queue unchanged and is retried by the periodic
                // worker. It must never escape `viewModelScope`: an uncaught main-thread exception
                // here would close the app while the user is simply looking at their photos.
            }
        }
    }

    fun refresh() {
        repository.refresh()
    }

    override fun onCleared() {
        changeWatcher.stop()
        changeSyncJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Trailing-debounce window for MediaStore change bursts (~10s of quiet). */
        const val MEDIA_CHANGE_SYNC_DEBOUNCE_MS = 10_000L
    }
}
