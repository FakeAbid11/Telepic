package com.telepic.ui.screens.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.media.AlbumRepository
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.Album
import com.telepic.domain.media.CustomAlbum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AlbumsStatus {
    data object Loading : AlbumsStatus
    data class Ready(val albums: List<Album>) : AlbumsStatus
    data object Empty : AlbumsStatus
    data object Error : AlbumsStatus
}

/**
 * Backs the Albums screen: the device's folder albums plus the user's own.
 *
 * The two are loaded differently on purpose. Folder albums come from a MediaStore scan, so they are a
 * one-shot [refresh] with explicit Loading/Empty/Error states. User-created albums come from Room, so
 * they are a *flow* that stays current on its own — creating, renaming or deleting one needs no refresh
 * call, and the grid updates because the database said so rather than because the screen remembered to
 * ask. [status] therefore describes only the folder scan; the screen combines the two.
 */
class AlbumsViewModel(
    private val repository: AlbumRepository,
    private val customAlbumRepository: CustomAlbumRepository? = null,
) : ViewModel() {

    private val _status = MutableStateFlow<AlbumsStatus>(AlbumsStatus.Loading)
    val status: StateFlow<AlbumsStatus> = _status.asStateFlow()

    /** True while a user-initiated re-read is in flight, so the pull-to-refresh indicator shows. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** The user's own albums, most recently touched first. Empty when the feature is not wired. */
    val customAlbums: StateFlow<List<CustomAlbum>> =
        (customAlbumRepository?.albums ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Re-read the folder albums.
     *
     * [isRefreshing] is what keeps a pull-to-refresh from replacing the grid with a full-screen
     * loader: setting [status] to [AlbumsStatus.Loading] unconditionally would swap the albums the
     * user is looking at for a spinner, which is the opposite of what pulling down is for. Only a
     * read with nothing on screen shows the loading state.
     *
     * "Something on screen" is deliberately not the same as "has albums": the empty and the error
     * states are both something the user can see, and both are the states a user is most likely to
     * pull down from. Replacing either with a skeleton made the gesture give no feedback at all —
     * the spinner appeared and the pull indicator never did.
     */
    fun refresh() {
        val hasScreenState = _status.value !is AlbumsStatus.Loading
        if (!hasScreenState) _status.value = AlbumsStatus.Loading
        _isRefreshing.value = hasScreenState
        viewModelScope.launch {
            try {
                val albums = repository.albums()
                _status.value = if (albums.isEmpty()) AlbumsStatus.Empty else AlbumsStatus.Ready(albums)
            } catch (c: CancellationException) {
                // A cancelled load is not an error; propagate so the scope honors cancellation
                // rather than showing a spurious Error state.
                throw c
            } catch (_: Exception) {
                // A genuine query/permission failure is distinct from an empty library. On a refresh
                // the previous list is not kept either: showing stale albums as if they were current
                // would be the same class of claim this app refuses to make elsewhere.
                _status.value = AlbumsStatus.Error
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Creates a user-created album.
     *
     * Nothing is reported back: [customAlbums] is observed, so an album that was created appears and one
     * that was not does not. A blank name is refused by the repository rather than by a check here, so
     * the rule has exactly one home.
     */
    fun createAlbum(name: String) {
        val repo = customAlbumRepository ?: return
        persist { repo.create(name) }
    }

    /** Renames a user-created album. */
    fun renameAlbum(albumId: Long, name: String) {
        val repo = customAlbumRepository ?: return
        persist { repo.rename(albumId, name) }
    }

    /** Deletes a user-created album. The media itself is untouched; only the album goes. */
    fun deleteAlbum(albumId: Long) {
        val repo = customAlbumRepository ?: return
        persist { repo.delete(albumId) }
    }

    /**
     * The one exception boundary for this screen's writes.
     *
     * Anything that escapes `viewModelScope` is an uncaught exception on the main thread and the app
     * closes itself with no message. Nothing is claimed when a write fails: the album flow is the honest
     * report, so a create that did not land simply produces no new album.
     */
    private fun persist(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // The observed album list is still the report, but a failed write now leaves a trace
                // instead of vanishing.
                TelepicLog.warn("AlbumsViewModel", "an album change could not be saved", throwable)
            }
        }
    }
}
