package com.telepic.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.telepic.R
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A bulk organisation outcome for the message bar.
 *
 * [id] is the *event's* identity, so two identical outcomes still re-announce instead of being
 * conflated by a `StateFlow` of the message alone.
 */
data class BulkOrganizationReport(
    val id: Long,
    val outcome: Outcome,
) {
    sealed interface Outcome {
        /**
         * [added] items were newly favourited; [already] were favourites already.
         *
         * Both numbers are reported because the action is **one-way**: a mixed selection would
         * otherwise be reported as wholly new.
         */
        data class Favorited(val added: Int, val already: Int) : Outcome

        /** [ids] were moved to Trash. They can be restored. */
        data class Trashed(val ids: List<Long>) : Outcome

        /** Nothing could be changed. */
        data object Failed : Outcome
    }
}

/**
 * The bulk favourite / trash actions, shared by the Photos timeline and album contents.
 *
 * Both screens offer the same two verbs over the same selection, so the behaviour lives here once
 * instead of being written twice — the selection block itself is already duplicated between the two
 * ViewModels, and this is deliberately not a third copy of it.
 *
 * **Favourite is one-way.** A toggle would be ambiguous over a mixed selection (is the button
 * turning the first item on or off?), so it *sets* the flag. Un-favouriting stays where it
 * already is: the Favorites collection's "Remove from favorites".
 *
 * **Trash is reversible.** The ids that were moved are kept so the bar's Undo can restore exactly
 * those, and a *partial* write is never reported as a success: a failure reports the failure, and
 * the items that did move are recovered from the Trash collection like any other.
 */
class BulkOrganizationController(
    private val repository: MediaOrganizationRepository,
    private val scope: CoroutineScope,
) {
    private val _report = MutableStateFlow<BulkOrganizationReport?>(null)
    val report: StateFlow<BulkOrganizationReport?> = _report.asStateFlow()

    private val _isFavoriting = MutableStateFlow(false)
    val isFavoriting: StateFlow<Boolean> = _isFavoriting.asStateFlow()

    private val _isTrashing = MutableStateFlow(false)
    val isTrashing: StateFlow<Boolean> = _isTrashing.asStateFlow()

    /** The ids the last successful trash moved, so Undo can restore exactly those. */
    private var lastTrashed: List<Long> = emptyList()

    /**
     * Mark every selected item as a favourite.
     *
     * [alreadyFavorites] is the caller's own snapshot of the favourites — the same one that draws
     * the star badges on the tiles — so the reported split agrees with what the user can see.
     *
     * [onLanded] runs only after the write has landed, so the caller can clear its selection then:
     * a failed write must leave the selection standing so the user can simply tap again.
     */
    fun favorite(ids: List<Long>, alreadyFavorites: Set<Long>, onLanded: () -> Unit) {
        if (ids.isEmpty()) return
        if (_isFavoriting.value) return
        _isFavoriting.value = true
        scope.launch {
            try {
                val already = ids.count { it in alreadyFavorites }
                // Only the ones that are not favourites yet are written: re-writing one would reset
                // the moment it was favourited for no gain.
                ids.forEach { id -> if (id !in alreadyFavorites) repository.setFavorite(id, true) }
                _report.value = BulkOrganizationReport(
                    id = System.nanoTime(),
                    outcome = BulkOrganizationReport.Outcome.Favorited(
                        added = ids.size - already,
                        already = already,
                    ),
                )
                onLanded()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // Nothing is claimed about work that did not happen, and no Undo is offered for a
                // write that may only have landed in part.
                TelepicLog.warn("BulkOrganization", "the selection could not be favourited", throwable)
                _report.value = BulkOrganizationReport(
                    id = System.nanoTime(),
                    outcome = BulkOrganizationReport.Outcome.Failed,
                )
            } finally {
                _isFavoriting.value = false
            }
        }
    }

    /**
     * Move every selected item to Trash.
     *
     * [onLanded] runs only after every item has moved, so the caller can clear its selection then.
     */
    fun trash(ids: List<Long>, onLanded: () -> Unit) {
        if (ids.isEmpty()) return
        if (_isTrashing.value) return
        _isTrashing.value = true
        scope.launch {
            try {
                ids.forEach { id -> repository.moveToTrash(id) }
                lastTrashed = ids
                _report.value = BulkOrganizationReport(
                    id = System.nanoTime(),
                    outcome = BulkOrganizationReport.Outcome.Trashed(ids),
                )
                onLanded()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // A partial write is not reported as a success: the bar says it failed, and the items
                // that did move are restored from the Trash collection like any other.
                TelepicLog.warn("BulkOrganization", "the selection could not be moved to trash", throwable)
                _report.value = BulkOrganizationReport(
                    id = System.nanoTime(),
                    outcome = BulkOrganizationReport.Outcome.Failed,
                )
            } finally {
                _isTrashing.value = false
            }
        }
    }

    /** Undo of [trash]: restore exactly the items it moved. */
    fun undoTrash() {
        val ids = lastTrashed
        if (ids.isEmpty()) return
        lastTrashed = emptyList()
        scope.launch {
            try {
                ids.forEach { id -> repository.restoreFromTrash(id) }
                // The bar disappears on the same signal that says the restore landed.
                _report.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("BulkOrganization", "the trashed items could not be restored", throwable)
                _report.value = BulkOrganizationReport(
                    id = System.nanoTime(),
                    outcome = BulkOrganizationReport.Outcome.Failed,
                )
            }
        }
    }

    fun consumeMessage() {
        _report.value = null
    }
}

/**
 * The confirmation before a bulk move to Trash.
 *
 * The single-item trash in the Viewer confirms too (`viewer_trash_title`), and a bulk move is the
 * same decision with more of the user's library at stake — so it is confirmed, and it says how many.
 */
@Composable
fun ConfirmTrashDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.photos_trash_title, count, count)) },
        text = { Text(pluralStringResource(R.plurals.photos_trash_body, count, count)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.photos_trash_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.photos_selection_cancel))
            }
        },
    )
}

/**
 * The message bar for a bulk organisation outcome.
 *
 * A trashed selection offers an Undo, because moving something to Trash is reversible and the
 * Viewer's own trash does the same. A favourited selection does not: the star is already on the
 * tiles, and there is nothing to undo.
 */
@Composable
fun BulkOrganizationMessageBar(
    report: BulkOrganizationReport,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val outcome = report.outcome) {
        is BulkOrganizationReport.Outcome.Favorited -> TransientMessageBar(
            message = stringResource(R.string.photos_favorited_result, outcome.added, outcome.already),
            onDismiss = onDismiss,
            messageId = report.id,
            modifier = modifier,
        )
        is BulkOrganizationReport.Outcome.Trashed -> TransientMessageBar(
            message = pluralStringResource(R.plurals.photos_trashed_result, outcome.ids.size, outcome.ids.size),
            onDismiss = onDismiss,
            messageId = report.id,
            actionLabel = stringResource(R.string.viewer_trashed_undo),
            onAction = onUndo,
            modifier = modifier,
        )
        BulkOrganizationReport.Outcome.Failed -> TransientMessageBar(
            message = stringResource(R.string.settings_action_failed),
            onDismiss = onDismiss,
            messageId = report.id,
            modifier = modifier,
        )
    }
}
