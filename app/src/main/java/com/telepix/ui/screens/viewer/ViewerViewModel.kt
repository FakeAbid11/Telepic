package com.telepix.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.media.LocalMediaLookup
import com.telepix.data.media.NeighborDirection
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState(initialSource))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

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

    private fun load(target: MediaSource) {
        viewModelScope.launch {
            val resolved = when (target) {
                is MediaSource.Local -> resolveLocal(target)
                is MediaSource.Cloud -> resolveCloud(target)
            }
            // Preserve a download already completed for this source across reloads.
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
