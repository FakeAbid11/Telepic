package com.telepix.ui.screens.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepix.data.media.AlbumRepository
import com.telepix.domain.media.Album
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AlbumsStatus {
    data object Loading : AlbumsStatus
    data class Ready(val albums: List<Album>) : AlbumsStatus
    data object Empty : AlbumsStatus
    data object Error : AlbumsStatus
}

/**
 * Loads local albums (MediaStore buckets) for the Albums screen. Permission gating is handled by
 * the caller (the same model as Photos); this only queries once access is available and exposes
 * distinct loading / empty / error states so a failure is never shown as "no albums".
 */
class AlbumsViewModel(
    private val repository: AlbumRepository,
) : ViewModel() {

    private val _status = MutableStateFlow<AlbumsStatus>(AlbumsStatus.Loading)
    val status: StateFlow<AlbumsStatus> = _status.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _status.value = AlbumsStatus.Loading
        viewModelScope.launch {
            val albums = runCatching { repository.albums() }
            _status.value = albums.fold(
                onSuccess = { if (it.isEmpty()) AlbumsStatus.Empty else AlbumsStatus.Ready(it) },
                onFailure = { AlbumsStatus.Error },
            )
        }
    }
}
