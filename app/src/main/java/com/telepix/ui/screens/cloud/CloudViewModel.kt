package com.telepix.ui.screens.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.cloud.CloudRepository
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.CloudUiState
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

    // Locally available preview file paths, keyed by messageId. Populated lazily on scroll.
    private val _previews = MutableStateFlow<Map<Long, String>>(emptyMap())
    val previews: StateFlow<Map<Long, String>> = _previews.asStateFlow()

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    /** Request a lightweight preview for [media] (never the original). Idempotent per item. */
    fun loadPreview(media: CloudMedia) {
        if (_previews.value.containsKey(media.messageId) || media.previewFileId == null) return
        viewModelScope.launch {
            repository.getPreview(media)?.let { preview ->
                _previews.update { it + (media.messageId to preview.localPath) }
            }
        }
    }

    fun downloadOriginal(media: CloudMedia) {
        viewModelScope.launch { repository.downloadOriginal(media) }
    }

    /** Maps the current status to whether a background refresh spinner should show. */
    val isBusy: Boolean get() = uiState.value.status is CloudStatus.Refreshing
}
