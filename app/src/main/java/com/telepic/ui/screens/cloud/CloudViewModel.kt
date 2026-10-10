package com.telepic.ui.screens.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.cloud.CloudRepository
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudPreviewState
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns Cloud screen state and the asynchronous cloud work. Initialization is non-blocking, so
 * local Photos stay usable while the Telegram cloud resolves. No uploads are triggered here.
 */
class CloudViewModel(
    private val repository: CloudRepository,
) : ViewModel() {

    val uiState: StateFlow<CloudUiState> =
        combine(repository.status, repository.destinationTitle, repository.media) { status, title, media ->
            CloudUiState(status = status, destinationTitle = title, media = media)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CloudUiState(),
        )

    init {
        viewModelScope.launch { repository.prepare() }
    }

    // Per-item preview lifecycle, keyed by messageId. Populated lazily on scroll; failures are
    // tracked so the tile can offer a retry instead of a permanent placeholder.
    private val _previews = MutableStateFlow<Map<Long, CloudPreviewState>>(emptyMap())
    val previews: StateFlow<Map<Long, CloudPreviewState>> = _previews.asStateFlow()

    /** Re-pulls the destination list and clears cached preview states so failed items retry. */
    fun refresh() {
        _previews.value = emptyMap()
        viewModelScope.launch { repository.refresh() }
    }

    /**
     * Request a lightweight preview for [media] (never the original). Idempotent per item: an
     * in-flight, loaded, or already-failed request is not re-issued here — a retryable failure is
     * recovered via [retryPreview] so we never loop on a broken fetch.
     */
    fun loadPreview(media: CloudMedia) {
        if (_previews.value.containsKey(media.messageId)) return
        if (media.previewFileId == null) {
            _previews.update { it + (media.messageId to CloudPreviewState.Failed(retryable = false)) }
            return
        }
        fetchPreview(media)
    }

    /** Re-attempts a preview whose prior fetch failed but which has a preview file to fetch. */
    fun retryPreview(media: CloudMedia) {
        if (media.previewFileId == null) return
        _previews.update { it - media.messageId }
        fetchPreview(media)
    }

    private fun fetchPreview(media: CloudMedia) {
        _previews.update { it + (media.messageId to CloudPreviewState.Loading) }
        viewModelScope.launch {
            val state = try {
                val preview = repository.getPreview(media)
                if (preview != null) CloudPreviewState.Loaded(preview.localPath) else CloudPreviewState.Failed(retryable = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Surfaced as a visible Failed state (with retry), never swallowed silently.
                CloudPreviewState.Failed(retryable = true)
            }
            _previews.update { it + (media.messageId to state) }
        }
    }

    fun downloadOriginal(media: CloudMedia) {
        viewModelScope.launch { repository.downloadOriginal(media) }
    }

    /** Maps the current status to whether a background refresh spinner should show. */
    val isBusy: Boolean get() = uiState.value.status is CloudStatus.Refreshing
}
