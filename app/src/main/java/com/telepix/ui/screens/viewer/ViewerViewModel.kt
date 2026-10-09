package com.telepix.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.media.LocalMediaLookup
import com.telepix.data.media.NeighborDirection
import com.telepix.data.organization.MediaOrganizationRepository
import com.telepix.data.restore.RestoreRepository
import com.telepix.data.restore.RestoreResult
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.navigation.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState(initialSource))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** Organization flags for the current local item; all-false for cloud items or when unwired. */
    private val _flags = MutableStateFlow(ViewerFlags())
    val flags: StateFlow<ViewerFlags> = _flags.asStateFlow()

    /** Restore-to-device state for the current cloud item (idle unless a restore is requested). */
    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    init {
        load(initialSource)
    }

    fun showNext() {
        _uiState.value.next?.let(::open)
    }

    fun showPrevious() {
        _uiState.value.previous?.let(::open)
    }

    /** Navigate to another source (used by next/previous and, if wired, a pager). */
    fun open(newSource: MediaSource) {
        _uiState.value = ViewerUiState(newSource, ViewerStatus.LOADING)
        load(newSource)
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
        _uiState.value = current.copy(download = CloudDownload.Downloading)
        viewModelScope.launch {
            val local = runCatching { cloudRepository.downloadOriginal(cloud.media) }.getOrNull()
            _uiState.value = if (local != null) {
                current.copy(
                    download = CloudDownload.Available(local.localPath),
                    item = cloud.copy(originalPath = local.localPath),
                )
            } else {
                current.copy(download = CloudDownload.Unavailable)
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
        _restore.value = RestoreState.Restoring
        viewModelScope.launch {
            val result = runCatching { repo.restore(cloud.media) }.getOrElse {
                RestoreResult.Failed(com.telepix.data.restore.RestoreFailure.DOWNLOAD_UNAVAILABLE)
            }
            _restore.value = when (result) {
                is RestoreResult.Restored -> RestoreState.Restored(result.localUri)
                is RestoreResult.Failed -> RestoreState.Failed(result.reason.name)
            }
        }
    }

    private fun load(target: MediaSource) {
        viewModelScope.launch {
            _restore.value = RestoreState.Idle
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
            _uiState.value = resolved
        }
    }

    private suspend fun resolveLocal(s: MediaSource.Local): ViewerUiState {
        val media = localLookup.byId(s.mediaId) ?: return ViewerUiState(s, ViewerStatus.MISSING)
        val newer = localLookup.neighborId(s.mediaId, NeighborDirection.NEWER)
        val older = localLookup.neighborId(s.mediaId, NeighborDirection.OLDER)
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
