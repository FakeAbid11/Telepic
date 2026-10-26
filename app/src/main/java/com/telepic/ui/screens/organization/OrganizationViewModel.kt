package com.telepic.ui.screens.organization

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.organization.DeleteRequest
import com.telepic.data.organization.LocalMediaDeleter
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.data.organization.PermanentMediaCleanup
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.LocalMedia
import com.telepic.navigation.OrganizationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a collection screen can show.
 *
 * [Error] exists because the alternative was a lie: the media lookup used to collapse every failure
 * into an empty list, so a provider that refused to answer was rendered as "Trash is empty" — a
 * statement about the user's data, made without having read it. `null`-means-loading could not
 * express this, because a failed read is not a read in progress.
 */
sealed interface OrgItemsState {
    data object Loading : OrgItemsState
    data class Ready(val items: List<LocalMedia>) : OrgItemsState
    data object Error : OrgItemsState
}

/**
 * Transient feedback the collection screen must report; types, never magic strings.
 *
 * The success cases are here because the collection used to report **only** failures: un-favouriting
 * forty items produced a grid that simply changed, with nothing saying the write had landed.
 */
sealed interface OrgMessage {
    /** A consented permanent delete could not be executed. */
    data class DeleteFailed(val count: Int) : OrgMessage

    /** [count] items left this collection (unfavourited / unarchived / restored). */
    data class MembershipChanged(val count: Int) : OrgMessage

    /** [count] items were permanently deleted from the device. */
    data class DeletedFromDevice(val count: Int) : OrgMessage

    /** Some items were deleted and others remain; neither half may be reported as a full success. */
    data class PartiallyDeleted(val deleted: Int, val failed: Int) : OrgMessage
}

/**
 * Backs a single Favorites / Archive / Trash collection. It reads only the ids for [kind] from Room
 * and resolves the media in one batched lookup, so it never loads the whole library and stays
 * independent of backup state. Undo (unfavorite / unarchive / restore) and permanent delete act
 * through the injected repository/deleter — the latter trusts only a consented result.
 *
 * Selection is multi-item, matching the Photos timeline: a long press starts a selection, a tap
 * toggles, and the bulk bar acts on all of it. It is a plain [MutableStateFlow] of an
 * insertion-ordered map keyed by the stable MediaStore id — never a grid position — so it stays
 * consistent with the UI on the frame it changes. The whole collection is already in memory (it is a
 * Room id-set resolved in one batched lookup, not a pager), so "select all" here is exact and needs
 * no read of its own.
 */
class OrganizationViewModel(
    val kind: OrganizationKind,
    private val organizationRepository: MediaOrganizationRepository,
    private val localLookup: LocalMediaLookup,
    private val deleter: LocalMediaDeleter,
    private val cleanup: PermanentMediaCleanup,
) : ViewModel() {

    /**
     * Test/legacy constructor. It preserves the old organization-row-only cleanup for callers that
     * do not have the app database; production must pass [RoomPermanentMediaCleanup] so album
     * membership and location rows are removed too.
     */
    constructor(
        kind: OrganizationKind,
        organizationRepository: MediaOrganizationRepository,
        localLookup: LocalMediaLookup,
        deleter: LocalMediaDeleter,
    ) : this(
        kind = kind,
        organizationRepository = organizationRepository,
        localLookup = localLookup,
        deleter = deleter,
        cleanup = OrganizationOnlyCleanup(organizationRepository),
    )

    private val _message = MutableStateFlow<OrgMessage?>(null)
    val message: StateFlow<OrgMessage?> = _message.asStateFlow()

    /**
     * A counter that advances with every message, so two identical reports are still two events.
     *
     * The screen's bar is keyed on this. Keying it on the message *value* conflated two identical
     * failures inside the bar's dismissal window: the second one produced no new bar, no new timer
     * and no reject haptic.
     */
    private val _messageId = MutableStateFlow(0L)
    val messageId: StateFlow<Long> = _messageId.asStateFlow()

    private fun publishMessage(message: OrgMessage) {
        _message.value = message
        _messageId.value += 1
    }

    /** True while a permanent-delete request (one item or many) is in flight. */
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    /** True while an undo/restore batch is in flight. */
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    /** True while a user-initiated re-read is in flight, so the pull-to-refresh indicator shows. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val idsFlow: Flow<Set<Long>> = when (kind) {
        OrganizationKind.FAVORITES -> organizationRepository.favoriteIds
        OrganizationKind.ARCHIVE -> organizationRepository.archivedIds
        OrganizationKind.TRASH -> organizationRepository.trashedIds
    }

    /** Bumped by [refresh] to force a re-read of an id-set that has not itself changed. */
    private val reloadTrigger = MutableStateFlow(0)

    val items: StateFlow<OrgItemsState> = combine(idsFlow, reloadTrigger) { ids, _ -> ids }
        .map { ids -> readItems(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrgItemsState.Loading)

    /**
     * Resolve the collection, keeping a failed read distinct from an empty one.
     *
     * `byIdList` propagates failures precisely so this can tell the two apart; the screen then offers
     * a retry instead of asserting that the user's items are gone. The in-flight refresh flag is
     * cleared here because this is the one place that knows the read has finished.
     */
    private suspend fun readItems(ids: Set<Long>): OrgItemsState {
        val state = try {
            OrgItemsState.Ready(localLookup.byIdList(ids))
        } catch (cancellation: CancellationException) {
            // A cancelled read is not a finished read: clearing the flag here would be a lie about
            // work that never completed, and *not* clearing it left the pull-to-refresh spinner
            // turning for good. The flag is cleared in a `finally` below instead.
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("OrganizationViewModel", "the collection could not be read; showing the retry state", throwable)
            OrgItemsState.Error
        } finally {
            _isRefreshing.value = false
        }
        return state
    }

    /** Re-read the collection. Also the retry offered on [OrgItemsState.Error]. */
    fun refresh() {
        _isRefreshing.value = true
        reloadTrigger.update { it + 1 }
    }

    // --- Multi-selection ------------------------------------------------------------------------

    private val _selected = MutableStateFlow<Map<Long, LocalMedia>>(emptyMap())
    val selectedItems: StateFlow<Map<Long, LocalMedia>> = _selected.asStateFlow()

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

    /**
     * Replaces the selection with the whole collection.
     *
     * [items] is what the screen is showing, which here *is* the collection: it is a fully-resolved
     * id-set rather than a pager, so unlike the timeline there is no unloaded tail to be honest about.
     */
    fun selectAll(items: List<LocalMedia>) {
        if (items.isEmpty()) return
        _selected.value = LinkedHashMap<Long, LocalMedia>(items.size).apply {
            items.forEach { put(it.id, it) }
        }
    }

    fun clearSelection() {
        _selected.value = emptyMap()
    }

    /** Undo the collection membership for everything selected (unfavorite / unarchive / restore). */
    fun undoSelected() {
        val ids = _selected.value.keys.toList()
        if (ids.isEmpty()) return
        if (_isRestoring.value) return
        _isRestoring.value = true
        viewModelScope.launch {
            try {
                ids.forEach { id ->
                    when (kind) {
                        OrganizationKind.FAVORITES -> organizationRepository.setFavorite(id, false)
                        OrganizationKind.ARCHIVE -> organizationRepository.setArchived(id, false)
                        OrganizationKind.TRASH -> organizationRepository.restoreFromTrash(id)
                    }
                }
                clearSelection()
                // Reported only after the writes returned: the grid changing on its own is not a
                // confirmation, and a count posted optimistically would be a claim about work that
                // had not happened yet.
                publishMessage(OrgMessage.MembershipChanged(ids.size))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // The selection survives and the id-set flow re-emits, so a partial undo shows as
                // exactly what it was rather than as a success.
                TelepicLog.warn("OrganizationViewModel", "undoing the selection failed part-way", throwable)
            } finally {
                _isRestoring.value = false
            }
        }
    }

    /**
     * The system prompt currently outstanding, with the exact items it covers and the items that
     * still need a later round. Held here rather than saved in the screen because the ViewModel
     * outlives Activity recreation; it is cleared before acting so one result cannot delete twice.
     */
    private var pendingConsent: PendingConsent? = null

    private data class PendingConsent(
        val media: List<LocalMedia>,
        val remaining: List<LocalMedia>,
        val launcher: (IntentSender) -> Unit,
    )

    /** Counts across all consent rounds, so the final report describes the whole operation. */
    private val deletedThisOperation = LinkedHashSet<Long>()
    private val failedThisOperation = LinkedHashSet<Long>()

    /**
     * Request a permanent delete for the whole selection in [viewModelScope]: the delete work must
     * never be cancelled by the screen leaving composition (a rememberCoroutineScope would do exactly
     * that). [onNeedsConsent] hands the system intent-sender to the screen, which owns the Activity
     * result contract and resolves it back through [finishConsent].
     */
    fun requestDeleteSelected(onNeedsConsent: (IntentSender) -> Unit) {
        val selection = _selected.value.values.toList()
        if (selection.isEmpty()) return
        if (_isDeleting.value) return
        _isDeleting.value = true
        pendingConsent = null
        deletedThisOperation.clear()
        failedThisOperation.clear()
        viewModelScope.launch {
            // The deleter talks to MediaStore's delete-request API, which can throw for a provider
            // reason no branch here models. Without this boundary the exception escaped
            // viewModelScope (an uncaught main-thread crash) *and* left `_isDeleting` true forever, so
            // the delete bar spun with no way out. A failed request is reported and the bar is freed.
            runDeleteRound(selection, onNeedsConsent)
        }
    }

    /**
     * One delete attempt. API 30 usually resolves in one call; API 29 can return an exact split of
     * deleted/failed/consent-required/remaining, and this method continues with the remainder.
     */
    private suspend fun runDeleteRound(media: List<LocalMedia>, launcher: (IntentSender) -> Unit) {
        if (media.isEmpty()) {
            finishDeletion()
            return
        }
        val outcome = try {
            deleter.requestDeleteAll(media)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("OrganizationViewModel", "permanent-delete request failed", throwable)
            failedThisOperation += media.map { it.id }
            finishDeletion()
            return
        }
        when (outcome) {
            is DeleteRequest.NeedsConsent -> {
                confirmedDeleted(outcome.deletedIds)
                failedThisOperation += outcome.failedIds
                val consent = outcome.consentMedia.ifEmpty { media }
                pendingConsent = PendingConsent(consent, outcome.remainingMedia, launcher)
                launcher(outcome.intentSender)
            }
            is DeleteRequest.PartiallyDeleted -> {
                confirmedDeleted(outcome.deletedIds)
                failedThisOperation += outcome.failedIds
                finishDeletion()
            }
            DeleteRequest.Deleted -> {
                confirmedDeleted(media.map { it.id })
                finishDeletion()
            }
            DeleteRequest.Failed -> {
                failedThisOperation += media.map { it.id }
                finishDeletion()
            }
        }
    }

    /**
     * Forget exactly the ids the platform confirmed. The cleanup is one transaction over every
     * table that keys on a MediaStore id; a cleanup failure is logged because the file is already
     * gone and must not turn a real deletion into a claimed failure.
     */
    private suspend fun confirmedDeleted(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        deletedThisOperation += ids
        _selected.update { selected -> selected - ids.toSet() }
        try {
            cleanup.cleanup(ids)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("OrganizationViewModel", "could not forget deleted items", throwable)
        }
    }

    /** Publish the totals for this operation and release the busy state. */
    private fun finishDeletion() {
        pendingConsent = null
        _isDeleting.value = false
        val deleted = deletedThisOperation.size
        val failed = failedThisOperation.size
        when {
            deleted > 0 && failed > 0 -> publishMessage(OrgMessage.PartiallyDeleted(deleted, failed))
            deleted > 0 -> publishMessage(OrgMessage.DeletedFromDevice(deleted))
            failed > 0 -> publishMessage(OrgMessage.DeleteFailed(failed))
        }
        if (_selected.value.isEmpty()) clearSelection()
    }

    /**
     * Resolve the consent contract. Only a positive result forgets the items the prompt covered;
     * the unattempted remainder is then processed in a new round, which may ask for consent again.
     * Returns the platform result so the screen can react, matching the previous contract.
     */
    fun finishConsent(success: Boolean): Boolean {
        val pending = pendingConsent
        pendingConsent = null
        if (pending == null) {
            _isDeleting.value = false
            return success
        }
        if (!success) {
            // Already-confirmed items were cleaned as they were deleted; the prompt's items and the
            // unattempted remainder stay selected so the user can retry.
            _isDeleting.value = false
            return false
        }
        viewModelScope.launch {
            confirmedDeleted(pending.media.map { it.id })
            if (pending.remaining.isNotEmpty()) {
                runDeleteRound(pending.remaining, pending.launcher)
            } else {
                finishDeletion()
            }
        }
        return true
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/**
 * Legacy/test cleanup: organization rows only. Production supplies [RoomPermanentMediaCleanup],
 * which also removes album membership, cover hints and location rows.
 */
private class OrganizationOnlyCleanup(
    private val repository: MediaOrganizationRepository,
) : PermanentMediaCleanup {
    override suspend fun cleanup(ids: Collection<Long>) {
        ids.forEach { repository.remove(it) }
    }
}
