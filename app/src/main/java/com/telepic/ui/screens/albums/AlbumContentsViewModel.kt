package com.telepic.ui.screens.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.telepic.data.backup.BackupCoordinator
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.media.AlbumRepository
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.PhotosItem
import com.telepic.navigation.AlbumSource
import com.telepic.ui.components.BulkBackupReport
import kotlinx.coroutines.CancellationException
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
 * One album's contents: the same paged, day-grouped [PhotosItem] stream and backup-status snapshot as
 * the main Photos timeline, scoped to a single album. Reuses the album repositories so album media
 * behaves identically to the timeline (and never re-implements the grid). Selection + bulk backup
 * mirror the Photos timeline exactly — same engine, same stable-id semantics.
 *
 * Works for both kinds of album through [AlbumSource]. The only behavioural difference is membership: a
 * folder album's contents are whatever the filesystem holds, so they can be browsed and backed up but
 * not edited; a user-created album's contents are the user's own list, so they can also be removed from
 * it. [customAlbumId] is what the screen uses to decide whether to offer that.
 */
class AlbumContentsViewModel(
    private val source: AlbumSource,
    private val albumRepository: AlbumRepository,
    private val customAlbumRepository: CustomAlbumRepository? = null,
    backupStatusRepository: BackupStatusRepository,
    private val backupCoordinator: BackupCoordinator? = null,
    private val organizationRepository: MediaOrganizationRepository? = null,
) : ViewModel() {

    /** The user-created album's id, or null for a folder album. Drives the rename/delete affordances. */
    val customAlbumId: Long? = (source as? AlbumSource.Custom)?.albumId

    private val _title = MutableStateFlow(source.title)

    /**
     * The album's title.
     *
     * A flow rather than a value because a user-created album can be renamed — from this screen's own
     * overflow — and the stored name is observed rather than frozen into the navigation route.
     */
    val title: StateFlow<String> = _title.asStateFlow()

    val media: Flow<PagingData<PhotosItem>> = when (source) {
        is AlbumSource.Bucket -> albumRepository.mediaInBucket(source.bucketId)
        is AlbumSource.Custom ->
            customAlbumRepository?.mediaInAlbum(source.albumId) ?: flowOf(PagingData.empty())
    }.cachedIn(viewModelScope)

    init {
        // Archiving or trashing from the Viewer changes what this album contains, and a `cachedIn`
        // pager never re-queries on its own — so returning from the Viewer left the item listed with a
        // stale count, and this screen has no other way to refresh. The Photos timeline already re-reads
        // on this same signal (`PhotosViewModel`); the album did not, so the two screens disagreed about
        // the same photo. Both repositories are invalidated, because which one owns this album's pager
        // depends on which kind of album it is.
        if (organizationRepository != null) {
            viewModelScope.launch {
                combine(
                    organizationRepository.archivedIds,
                    organizationRepository.trashedIds,
                ) { archived, trashed -> archived + trashed }
                    .collect {
                        albumRepository.refresh()
                        customAlbumRepository?.refresh()
                    }
            }
        }
        // Keep the header in step with the stored name, including a rename made from the Albums grid.
        val albumId = customAlbumId
        if (albumId != null && customAlbumRepository != null) {
            viewModelScope.launch {
                customAlbumRepository.observeName(albumId).collect { name ->
                    if (name != null) _title.value = name
                }
            }
        }
    }

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

    /** Transient bulk-backup feedback for the message bar; null when nothing to report. The report
     * carries a unique id so an identical summary still re-announces instead of being conflated. */
    private val _selectionReport = MutableStateFlow<BulkBackupReport?>(null)
    val selectionReport: StateFlow<BulkBackupReport?> = _selectionReport.asStateFlow()

    /** True while [backupSelected] is hashing/enqueueing — the action bar shows a spinner. */
    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    /** True while a removal is in flight, so the action bar cannot start a second one. */
    private val _isRemoving = MutableStateFlow(false)
    val isRemoving: StateFlow<Boolean> = _isRemoving.asStateFlow()

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
     * new work, never a fabricated success. Re-entry while a run is in flight is ignored.
     */
    fun backupSelected() {
        val coordinator = backupCoordinator ?: return
        val items = _selected.value.values.toList()
        if (items.isEmpty()) return
        if (_isBackingUp.value) return
        _isBackingUp.value = true
        viewModelScope.launch {
            try {
                val summary = coordinator.backupAll(items)
                _selectionReport.value = BulkBackupReport(summary, System.nanoTime())
                clearSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // No report and no clearSelection: nothing is claimed about work that did not happen,
                // and the selection survives so the user can simply tap again.
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    /**
     * Removes the selection from this user-created album.
     *
     * A no-op for a folder album, where there is nothing to remove from: membership *is* the filesystem.
     * The media itself is never touched — this drops only the album's claim on it, which is the one thing
     * the user asked to undo.
     */
    fun removeSelectedFromAlbum() {
        val repository = customAlbumRepository ?: return
        val albumId = customAlbumId ?: return
        val ids = _selected.value.keys.toList()
        if (ids.isEmpty()) return
        if (_isRemoving.value) return
        _isRemoving.value = true
        viewModelScope.launch {
            try {
                ids.forEach { repository.removeMedia(albumId, it) }
                clearSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The selection survives and the grid re-reads from Room, so a partial removal shows as
                // exactly what it was rather than as a success.
            } finally {
                _isRemoving.value = false
            }
        }
    }

    /** Renames this user-created album. A no-op for a folder album, whose name comes from the device. */
    fun rename(name: String) {
        val repository = customAlbumRepository ?: return
        val albumId = customAlbumId ?: return
        viewModelScope.launch {
            try {
                repository.rename(albumId, name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The observed name is the report: a rename that did not land leaves the old title.
            }
        }
    }

    /** Deletes this user-created album. [onDeleted] runs only once the write has landed. */
    fun delete(onDeleted: () -> Unit) {
        val repository = customAlbumRepository ?: return
        val albumId = customAlbumId ?: return
        viewModelScope.launch {
            try {
                repository.delete(albumId)
                onDeleted()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Stay on the album: the delete did not happen, so navigating away would be a lie.
            }
        }
    }

    fun consumeSelectionMessage() {
        _selectionReport.value = null
    }
}
