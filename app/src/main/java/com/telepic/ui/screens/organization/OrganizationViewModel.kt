package com.telepic.ui.screens.organization

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.organization.DeleteRequest
import com.telepic.data.organization.LocalMediaDeleter
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.media.LocalMedia
import com.telepic.navigation.OrganizationKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs a single Favorites / Archive / Trash collection. It reads only the ids for [kind] from Room
 * and resolves the media in one batched lookup, so it never loads the whole library and stays
 * independent of backup state. Undo (unfavorite / unarchive / restore) and permanent delete act
 * through the injected repository/deleter — the latter trusts only a consented result.
 */
class OrganizationViewModel(
    val kind: OrganizationKind,
    private val organizationRepository: MediaOrganizationRepository,
    private val localLookup: LocalMediaLookup,
    private val deleter: LocalMediaDeleter,
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val idsFlow: Flow<Set<Long>> = when (kind) {
        OrganizationKind.FAVORITES -> organizationRepository.favoriteIds
        OrganizationKind.ARCHIVE -> organizationRepository.archivedIds
        OrganizationKind.TRASH -> organizationRepository.trashedIds
    }

    // null = the id-set has not resolved yet (loading); an empty list is a genuinely empty collection.
    val items: StateFlow<List<LocalMedia>?> = idsFlow
        .map { ids -> localLookup.byIdList(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Undo the collection membership for an item (unfavorite / unarchive / restore). */
    fun undo(id: Long) = viewModelScope.launch {
        when (kind) {
            OrganizationKind.FAVORITES -> organizationRepository.setFavorite(id, false)
            OrganizationKind.ARCHIVE -> organizationRepository.setArchived(id, false)
            OrganizationKind.TRASH -> organizationRepository.restoreFromTrash(id)
        }
        onChanged()
    }

    /** Ask to permanently delete a trashed file. Returns the outcome the UI must act on. */
    suspend fun requestDeleteForever(media: LocalMedia): DeleteRequest = deleter.requestDelete(media)

    /** Called only after a consented delete reports success: forget the item entirely. */
    fun onDeleteConfirmed(id: Long) = viewModelScope.launch {
        organizationRepository.markDeletedFromStore(id)
        organizationRepository.remove(id)
        onChanged()
    }

    fun reportDeleteFailed() {
        _message.value = DELETE_FAILED_KEY
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        const val DELETE_FAILED_KEY = "delete_failed"
    }
}
