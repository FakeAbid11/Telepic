package com.telepic.ui.screens.organization

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.navigation.OrganizationKind
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.MediaGridSkeleton
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SelectAllAction
import com.telepic.ui.components.SelectionTopBar
import com.telepic.ui.components.TransientMessageBar
import com.telepic.ui.components.rememberTelepicHaptics
import com.telepic.ui.screens.photos.MediaTile
import com.telepic.ui.theme.TelepicTokens

/**
 * A Favorites / Archive / Trash collection (Phase 10). Thumbnails only (no full decode); a long press
 * starts a multi-item selection and a tap toggles within it, exactly as the Photos timeline behaves,
 * so a bulk restore or a bulk permanent delete is one action rather than one action per item.
 * Permanent delete is explicit, confirmed, and delegated to Android's consent flow — items are only
 * forgotten after a consented success, and a trashed item is never implied to be gone from the device
 * while only the app-level flag is set.
 *
 * A failed read is its own state, not an empty one: the collection used to render "Trash is empty"
 * over a query that never answered, which is a claim about the user's data the app had not earned.
 *
 * Because the whole collection is already resolved in memory (a Room id-set read in one batched
 * lookup, not a pager), "Select all" here is exact and carries a real total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationScreen(
    viewModel: OrganizationViewModel,
    onBack: () -> Unit,
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageId by viewModel.messageId.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val isRestoring by viewModel.isRestoring.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectionActive = selectedItems.isNotEmpty()
    val spacing = TelepicTokens.spacing
    val haptics = rememberTelepicHaptics()

    // Not saved across process death: the dialog's subject is the in-memory selection, and
    // `selectedItems` resets to empty on its own. Restoring the dialog without the selection rendered
    // "delete 0 items" — with a confirm button that still fired.
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // The result is applied to the ids the ViewModel is holding for it, so the screen only has to
    // report whether the user accepted — it never has to remember which items the prompt covered.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.finishConsent(result.resultCode == Activity.RESULT_OK)
    }

    // Back leaves selection mode first, before leaving the collection.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }

    // A refused delete says "no" to the hand as well as on screen: the message bar is the only other
    // signal, and it appears exactly where the user is not looking.
    LaunchedEffect(message) {
        if (message is OrgMessage.DeleteFailed || message is OrgMessage.PartiallyDeleted) {
            haptics.reject()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionActive) {
                    SelectionTopBar(
                        count = selectedItems.size,
                        onCancel = viewModel::clearSelection,
                        // The total is real here: the collection is fully resolved, not a loaded page.
                        selectAll = SelectAllAction(
                            onSelectAll = {
                                (items as? OrgItemsState.Ready)?.let { viewModel.selectAll(it.items) }
                            },
                            total = (items as? OrgItemsState.Ready)?.items?.size,
                        ),
                    )
                } else {
                    ScreenHeader(
                        title = stringResource(titleRes(viewModel.kind)),
                        onBack = onBack,
                    )
                }

                // The collection's data is a Room id-set, not a pager, so the only thing a refresh can
                // change is a file that appeared or vanished outside the app. The gesture is here for
                // consistency with the rest of the app; the indicator is driven by a real in-flight
                // flag rather than by the load state, so it never spins on first open.
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    when (val state = items) {
                        OrgItemsState.Loading -> MediaGridSkeleton(minCellSize = spacing.gridTileMinSize)
                        OrgItemsState.Error -> ErrorState(
                            title = stringResource(R.string.organization_error_title),
                            explanation = stringResource(R.string.organization_error_description),
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                        is OrgItemsState.Ready -> if (state.items.isEmpty()) {
                            EmptyState(
                                icon = emptyIcon(viewModel.kind),
                                title = stringResource(emptyTitleRes(viewModel.kind)),
                                description = stringResource(emptyDescRes(viewModel.kind)),
                            )
                        } else {
                            val longPressLabel = stringResource(R.string.media_long_press_select)
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = spacing.gridTileMinSize),
                                contentPadding = PaddingValues(spacing.gridGutter),
                                horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
                                verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(state.items, key = { it.id }) { media ->
                                    // The same tile the timeline and album grids use, rather than a
                                    // second copy of it. The inline version had already drifted: a
                                    // 0.25f selection scrim against the shared tile's 0.28f, no
                                    // backup or favourite badge, and an accessibility label that read
                                    // a *filename* ("Photo or video IMG_2026…") where every other grid
                                    // reads the type and date. Delegating makes "selecting looks and
                                    // sounds identical everywhere" true by construction.
                                    //
                                    // backupState is NONE on purpose: this collection is a Room
                                    // id-set, not the backup queue, so there is no status to read and
                                    // MediaTile draws no badge for NONE. Favourites are likewise not
                                    // read here — every item in the Favorites collection is one.
                                    MediaTile(
                                        media = media,
                                        backupState = MediaBackupVisualState.NONE,
                                        onClick = {
                                            if (selectionActive) {
                                                viewModel.toggleSelection(media)
                                            } else {
                                                onOpenMedia(media.id)
                                            }
                                        },
                                        // MediaTile fires the selection-start haptic itself, so this
                                        // must not buzz a second time.
                                        onLongClick = { viewModel.beginSelection(media) },
                                        onLongClickLabel = longPressLabel,
                                        selected = media.id in selectedItems,
                                        selectionActive = selectionActive,
                                    )
                                }
                            }
                        }
                    }

                    // Keyed on the ViewModel's counter, not on the message: two identical reports are
                    // still two events, and keying on the value conflated them — the second one
                    // produced no new bar, no new timer and no reject haptic.
                    //
                    // Anchored inside the grid's own area instead of at the screen's bottom edge:
                    // while a selection is active the action bar sits directly below this region, and
                    // the screen-level overlay drew the message on top of it — hiding the very
                    // controls the message was telling the user to retry with.
                    message?.let { pending ->
                        TransientMessageBar(
                            message = orgMessageText(pending, viewModel.kind),
                            onDismiss = viewModel::consumeMessage,
                            messageId = messageId,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                if (selectionActive) {
                    // Permanent delete only exists in Trash: the other two collections hold items that
                    // are still in the library, so the destructive action would be reachable from a
                    // screen that never offered it. Typed explicitly rather than inlined, so the
                    // lambda's type never depends on inference here.
                    val onDeleteForever: (() -> Unit)? =
                        if (viewModel.kind == OrganizationKind.TRASH) {
                            { showDeleteConfirm = true }
                        } else {
                            null
                        }
                    OrganizationActionBar(
                        kind = viewModel.kind,
                        restoreBusy = isRestoring,
                        deleteBusy = isDeleting,
                        onCancel = viewModel::clearSelection,
                        onUndo = viewModel::undoSelected,
                        onDeleteForever = onDeleteForever,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val count = selectedItems.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.trash_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.trash_delete_body_count, count, count)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        haptics.confirmDestructive()
                        // Deletion is async + consent-gated and runs in the ViewModel's scope so
                        // leaving this screen never cancels a real delete mid-flight. The consent
                        // result returns through the launcher above.
                        viewModel.requestDeleteSelected { intentSender ->
                            consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        }
                    },
                ) { Text(stringResource(R.string.trash_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.organization_cancel)) } },
        )
    }
}

/**
 * The sentence a collection message is reported with. Stated as the outcome, never the intent, and
 * numbered because every one of these now covers a whole selection.
 */
@Composable
private fun orgMessageText(message: OrgMessage, kind: OrganizationKind): String = when (message) {
    is OrgMessage.DeleteFailed ->
        pluralStringResource(R.plurals.trash_delete_failed_count, message.count)
    is OrgMessage.DeletedFromDevice ->
        pluralStringResource(R.plurals.organization_deleted_count, message.count, message.count)
    is OrgMessage.PartiallyDeleted ->
        stringResource(R.string.trash_delete_partial_result, message.deleted, message.failed)
    is OrgMessage.MembershipChanged -> when (kind) {
        OrganizationKind.FAVORITES ->
            pluralStringResource(R.plurals.organization_unfavorited_count, message.count, message.count)
        OrganizationKind.ARCHIVE ->
            pluralStringResource(R.plurals.organization_unarchived_count, message.count, message.count)
        OrganizationKind.TRASH ->
            pluralStringResource(R.plurals.organization_restored_count, message.count, message.count)
    }
}

/**
 * The bulk-action bar for a collection: Cancel, the collection's own undo, and — in Trash only —
 * Delete forever.
 *
 * Each action carries **its own** busy flag, and only the action that is actually running shows the
 * spinner; a single shared flag made the restore button animate while a delete ran, naming the wrong
 * thing. While either runs both are disabled, so a second write cannot start on top of the first.
 */
@Composable
private fun OrganizationActionBar(
    kind: OrganizationKind,
    restoreBusy: Boolean,
    deleteBusy: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onDeleteForever: (() -> Unit)?,
) {
    val spacing = TelepicTokens.spacing
    val anyBusy = restoreBusy || deleteBusy
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                // Scrollable rather than clipped, for the same reason the shared selection bar is:
                // these labels plus the gutters exceed a 320dp phone, and a 360dp one at a large
                // display font — and a plain Row neither wraps nor scrolls, so an action became
                // unreachable. The padding sits outside the scroll so the gutters stay put.
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
        ) {
            // Cancel stays enabled throughout: a write in flight is not a reason to trap the user in
            // selection mode, and the running action reports its own outcome when it returns.
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.organization_cancel))
            }
            TextButton(onClick = onUndo, enabled = !anyBusy) {
                if (restoreBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(spacing.xs))
                }
                Text(stringResource(undoLabelRes(kind)))
            }
            onDeleteForever?.let { delete ->
                TextButton(onClick = delete, enabled = !anyBusy) {
                    // "Delete forever" stays on screen while busy: replacing the label with a bare
                    // spinner left a destructive action identifiable only by its position in the row.
                    if (deleteBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(spacing.xs))
                    }
                    Text(stringResource(R.string.organization_delete_forever))
                }
            }
        }
    }
}

private fun titleRes(kind: OrganizationKind): Int = when (kind) {
    OrganizationKind.FAVORITES -> R.string.favorites_title
    OrganizationKind.ARCHIVE -> R.string.archive_title
    OrganizationKind.TRASH -> R.string.trash_title
}

private fun emptyTitleRes(kind: OrganizationKind): Int = when (kind) {
    OrganizationKind.FAVORITES -> R.string.favorites_empty_title
    OrganizationKind.ARCHIVE -> R.string.archive_empty_title
    OrganizationKind.TRASH -> R.string.trash_empty_title
}

private fun emptyDescRes(kind: OrganizationKind): Int = when (kind) {
    OrganizationKind.FAVORITES -> R.string.favorites_empty_description
    OrganizationKind.ARCHIVE -> R.string.archive_empty_description
    OrganizationKind.TRASH -> R.string.trash_empty_description
}

private fun undoLabelRes(kind: OrganizationKind): Int = when (kind) {
    OrganizationKind.FAVORITES -> R.string.organization_unfavorite
    OrganizationKind.ARCHIVE -> R.string.organization_unarchive
    OrganizationKind.TRASH -> R.string.organization_restore
}

private fun emptyIcon(kind: OrganizationKind) = when (kind) {
    OrganizationKind.FAVORITES -> Icons.Outlined.FavoriteBorder
    OrganizationKind.ARCHIVE -> Icons.Outlined.Archive
    OrganizationKind.TRASH -> Icons.Outlined.Delete
}
