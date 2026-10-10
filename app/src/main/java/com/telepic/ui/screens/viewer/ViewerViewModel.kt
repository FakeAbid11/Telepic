package com.telepic.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.MediaMetadata
import com.telepic.data.media.MediaMetadataReader
import com.telepic.data.media.NeighborDirection
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.data.restore.RestoreFailure
import com.telepic.data.restore.RestoreRepository
import com.telepic.data.restore.RestoreResult
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.MediaSource
import com.telepic.navigation.OrganizationKind
import com.telepic.navigation.ViewerScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The kind of media the Viewer is showing (a GIF stays distinct from a static photo). */
enum class ViewerKind { PHOTO, VIDEO, GIF }

enum class ViewerStatus { LOADING, READY, MISSING }

/** The download lifecycle for a cloud original — success only with a verified local path. */
sealed interface CloudDownload {
    data object Idle : CloudDownload
    data object Downloading : CloudDownload
    data class Available(val localPath: String) : CloudDownload
    data object Unavailable : CloudDownload // honest: unsupported on this build / failed
}

/** A resolved media item for display: a local content URI, or a cloud preview + optional original. */
sealed interface ViewerItem {
    data class Local(val media: LocalMedia) : ViewerItem
    data class Cloud(
        val media: CloudMedia,
        val previewPath: String?,
        val originalPath: String?,
    ) : ViewerItem
}

/** Organization flags for the current local item (Favorites / Archive / Trash). Independent of backup. */
data class ViewerFlags(
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
)

data class ViewerUiState(
    val source: MediaSource,
    val status: ViewerStatus = ViewerStatus.LOADING,
    val kind: ViewerKind = ViewerKind.PHOTO,
    val item: ViewerItem? = null,
    val previous: MediaSource? = null,
    val next: MediaSource? = null,
    val download: CloudDownload = CloudDownload.Idle,
    val dateMillis: Long? = null,
)

/**
 * Presentation metadata for the Viewer's Details sheet. Every field is null when it cannot be
 * obtained reliably — the UI omits null rows rather than showing a fabricated value. [captureMillis]
 * comes only from EXIF (never the file-modified time), and [latitude]/[longitude] only from EXIF GPS.
 */
data class MediaDetails(
    val fileName: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val captureMillis: Long?,
    val libraryDateMillis: Long,
    val libraryAddedMillis: Long?,
    val cameraMake: String?,
    val cameraModel: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/**
 * One Viewer session. Resolves a [MediaSource] strictly by its own identity — a local MediaStore id
 * via [LocalMediaLookup], a cloud (chatId, messageId) via the [CloudRepository] manifest — and
 * derives adjacent sources for next/previous without loading whole libraries. A missing item surfaces
 * [ViewerStatus.MISSING] rather than substituting another. Cloud originals download only on explicit
 * request, and success is reported only with a verified local path (never fabricated).
 */
class ViewerViewModel(
    initialSource: MediaSource,
    private val localLookup: LocalMediaLookup,
    private val cloudRepository: CloudRepository,
    private val organizationRepository: MediaOrganizationRepository? = null,
    private val restoreRepository: RestoreRepository? = null,
    private val scope: ViewerScope = ViewerScope.Global,
    private val metadataReader: MediaMetadataReader? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState(initialSource))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** Organization flags for the current local item; all-false for cloud items or when unwired. */
    private val _flags = MutableStateFlow(ViewerFlags())
    val flags: StateFlow<ViewerFlags> = _flags.asStateFlow()

    /** Details for the current LOCAL item, or null when not requested/available. Reset on navigation. */
    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    /** Restore-to-device state for the current cloud item (idle unless a restore is requested). */
    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    /** The in-flight item load; cancelled on every navigation so a slow resolve never lands stale. */
    private var loadJob: Job? = null

    init {
        load(initialSource)
    }

    fun showNext() {
        _uiState.value.next?.let(::open)
    }

    fun showPrevious() {
        _uiState.value.previous?.let(::open)
    }

    /** Navigate to another source (used by next/previous and by the swipe gesture). */
    fun open(newSource: MediaSource) {
        loadJob?.cancel()
        _uiState.value = ViewerUiState(newSource, ViewerStatus.LOADING)
        load(newSource)
    }

    /**
     * The Coil display model for a (possibly adjacent) source, so the screen can warm the image
     * cache for the swipe targets. Cloud sources return null: pre-resolving a preview would fire a
     * TDLib file request for every neighbor.
     */
    suspend fun displayModelFor(source: MediaSource): Any? = when (source) {
        is MediaSource.Local -> localLookup.byId(source.mediaId)?.contentUri
        is MediaSource.Cloud -> null
    }

    /**
     * Load Details for the current LOCAL item on demand — EXIF (capture/camera/GPS) is read off the
     * main thread via [metadataReader] and merged with the MediaStore fields already on the item. Cloud
     * items have no local file to read, so nothing loads; a missing reader yields MediaStore fields only.
     */
    fun loadDetails() {
        val media = (_uiState.value.item as? ViewerItem.Local)?.media ?: return
        viewModelScope.launch {
            val metadata = runCatching { metadataReader?.read(media.contentUri) }
                .getOrNull() ?: MediaMetadata()
            _details.value = MediaDetails(
                fileName = media.displayName,
                mimeType = media.mimeType,
                sizeBytes = media.sizeBytes,
                width = media.width,
                height = media.height,
                captureMillis = metadata.captureMillis,
                libraryDateMillis = media.dateMillis,
                libraryAddedMillis = metadata.libraryAddedMillis,
                cameraMake = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                latitude = metadata.location?.latitude,
                longitude = metadata.location?.longitude,
            )
        }
    }

    fun clearDetails() {
        _details.value = null
    }

    // --- Phase 10 organization actions (local items only) ------------------------------------

    fun toggleFavorite() = mutateFlags { repo, id, current ->
        val next = !current.isFavorite
        repo.setFavorite(id, next)
        current.copy(isFavorite = next)
    }

    fun toggleArchive() = mutateFlags { repo, id, current ->
        val next = !current.isArchived
        repo.setArchived(id, next)
        current.copy(isArchived = next)
    }

    /** Move the current local item to Trash (app-level; the file is preserved until a delete). */
    fun moveToTrash() = mutateFlags { repo, id, current ->
        repo.moveToTrash(id)
        current.copy(isTrashed = true)
    }

    private fun mutateFlags(action: suspend (MediaOrganizationRepository, Long, ViewerFlags) -> ViewerFlags) {
        val repo = organizationRepository ?: return
        val id = (_uiState.value.item as? ViewerItem.Local)?.media?.id ?: return
        viewModelScope.launch {
            _flags.value = action(repo, id, _flags.value)
        }
    }

    private suspend fun loadFlags(id: Long) {
        val repo = organizationRepository
        _flags.value = if (repo == null) {
            ViewerFlags()
        } else {
            ViewerFlags(
                isFavorite = repo.isFavorite(id),
                isArchived = repo.isArchived(id),
                isTrashed = repo.isTrashed(id),
            )
        }
    }

    /** Explicitly fetch a cloud original. Never auto-invoked by merely opening the Viewer. */
    fun downloadOriginal() {
        val current = _uiState.value
        val cloud = current.item as? ViewerItem.Cloud ?: return
        if (current.download is CloudDownload.Available) return
        val requestedSource = current.source
        _uiState.value = current.copy(download = CloudDownload.Downloading)
        viewModelScope.launch {
            val local = runCatching { cloudRepository.downloadOriginal(cloud.media) }.getOrNull()
            // Identity-guarded: navigating away mid-download must never resurrect the old item's state.
            _uiState.update { state ->
                if (state.source != requestedSource) return@update state
                if (local != null) {
                    val existing = state.item as? ViewerItem.Cloud ?: return@update state
                    state.copy(
                        download = CloudDownload.Available(local.localPath),
                        item = existing.copy(originalPath = local.localPath),
                    )
                } else {
                    state.copy(download = CloudDownload.Unavailable)
                }
            }
        }
    }

    /**
     * Restore the current cloud original into the device's public media storage — download, verify,
     * publish. Explicit user action only; success is reported only after a verified publication. A
     * device-gated or failed step leaves an honest failure, never a false "saved" state.
     */
    fun restoreToLocal() {
        val repo = restoreRepository ?: return
        val cloud = (_uiState.value.item as? ViewerItem.Cloud) ?: return
        if (_restore.value is RestoreState.Restoring) return
        val requestedSource = _uiState.value.source
        _restore.value = RestoreState.Restoring
        viewModelScope.launch {
            val result = runCatching { repo.restore(cloud.media) }.getOrElse {
                RestoreResult.Failed(RestoreFailure.DOWNLOAD_UNAVAILABLE)
            }
            // Guard: a restore that finishes after navigation must not claim the new item was saved.
            if (_uiState.value.source == requestedSource) {
                _restore.value = when (result) {
                    is RestoreResult.Restored -> RestoreState.Restored(result.localUri)
                    is RestoreResult.Failed -> RestoreState.Failed(result.reason.name)
                }
            }
        }
    }

    private fun load(target: MediaSource) {
        loadJob = viewModelScope.launch {
            _restore.value = RestoreState.Idle
            _details.value = null // a different item never leaks the previous item's details
            val resolved = when (target) {
                is MediaSource.Local -> resolveLocal(target)
                is MediaSource.Cloud -> resolveCloud(target)
            }
            // Cloud items have no organization flags; reset so the flag bar never leaks across items.
            if (resolved.item is ViewerItem.Local) {
                loadFlags((resolved.item as ViewerItem.Local).media.id)
            } else {
                _flags.value = ViewerFlags()
            }
            _uiState.update { if (it.source == resolved.source) resolved else it }
        }
    }

    private suspend fun resolveLocal(s: MediaSource.Local): ViewerUiState {
        val media = localLookup.byId(s.mediaId) ?: return ViewerUiState(s, ViewerStatus.MISSING)
        val (newer, older) = localNeighbors(s.mediaId)
        return ViewerUiState(
            source = s,
            status = ViewerStatus.READY,
            kind = media.type.toViewerKind(),
            item = ViewerItem.Local(media),
            previous = newer?.let { MediaSource.Local(it) },
            next = older?.let { MediaSource.Local(it) },
            dateMillis = media.dateMillis,
        )
    }

    /**
     * Resolves the adjacent local ids honoring the originating [scope]: the album's bucket, a curated
     * collection's own (newest-first) order, or the visible global timeline. Returns (previous, next)
     * as ids, where previous is the newer item and next the older one.
     */
    private suspend fun localNeighbors(id: Long): Pair<Long?, Long?> = when (scope) {
        ViewerScope.Global ->
            localLookup.neighborId(id, NeighborDirection.NEWER) to localLookup.neighborId(id, NeighborDirection.OLDER)
        is ViewerScope.Bucket ->
            localLookup.neighborId(id, NeighborDirection.NEWER, scope.bucketId) to
                localLookup.neighborId(id, NeighborDirection.OLDER, scope.bucketId)
        is ViewerScope.Collection -> collectionNeighbors(id, scope.kind)
    }

    // The collection's ordered id list is exactly what the originating screen shows (the Room id-set
    // resolved newest-first through the same bounded lookup), so next/previous stay inside it.
    private suspend fun collectionNeighbors(id: Long, kind: OrganizationKind): Pair<Long?, Long?> {
        val repo = organizationRepository
            ?: return localLookup.neighborId(id, NeighborDirection.NEWER) to localLookup.neighborId(id, NeighborDirection.OLDER)
        val ids = when (kind) {
            OrganizationKind.FAVORITES -> repo.favoriteIds
            OrganizationKind.ARCHIVE -> repo.archivedIds
            OrganizationKind.TRASH -> repo.trashedIds
        }.first()
        val ordered = localLookup.byIdList(ids).map { it.id } // newest-first, existing items only
        val index = ordered.indexOf(id)
        if (index < 0) return null to null
        return ordered.getOrNull(index - 1) to ordered.getOrNull(index + 1)
    }

    private suspend fun resolveCloud(s: MediaSource.Cloud): ViewerUiState {
        val manifest = cloudRepository.media.first()
        val index = manifest.indexOfFirst { it.chatId == s.chatId && it.messageId == s.messageId }
        if (index < 0) return ViewerUiState(s, ViewerStatus.MISSING)
        val media = manifest[index]
        val preview = runCatching { cloudRepository.getPreview(media) }.getOrNull()
        return ViewerUiState(
            source = s,
            status = ViewerStatus.READY,
            kind = media.mediaType.toViewerKind(),
            item = ViewerItem.Cloud(media, previewPath = preview?.localPath, originalPath = null),
            previous = manifest.getOrNull(index - 1)?.let { MediaSource.Cloud(it.chatId, it.messageId) },
            next = manifest.getOrNull(index + 1)?.let { MediaSource.Cloud(it.chatId, it.messageId) },
            download = CloudDownload.Idle,
            dateMillis = media.dateEpochSec?.times(1000L),
        )
    }
}

private fun MediaType.toViewerKind(): ViewerKind = when (this) {
    MediaType.PHOTO -> ViewerKind.PHOTO
    MediaType.VIDEO -> ViewerKind.VIDEO
    MediaType.GIF -> ViewerKind.GIF
}

private fun CloudMediaType.toViewerKind(): ViewerKind = when (this) {
    CloudMediaType.IMAGE -> ViewerKind.PHOTO
    CloudMediaType.VIDEO -> ViewerKind.VIDEO
    CloudMediaType.GIF -> ViewerKind.GIF
}

/** Restore-to-device state for the current cloud item. */
sealed interface RestoreState {
    data object Idle : RestoreState
    data object Restoring : RestoreState
    data class Restored(val localUri: String) : RestoreState
    data class Failed(val reasonName: String) : RestoreState
}
