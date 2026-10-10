package com.telepic.ui.screens.organization

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.data.organization.DeleteRequest
import com.telepic.navigation.OrganizationKind
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.TransientMessageBar
import com.telepic.ui.theme.TelepicTokens
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Archive
import kotlinx.coroutines.launch

/**
 * A Favorites / Archive / Trash collection (Phase 10). Thumbnails only (no full decode); selecting a
 * tile reveals context actions. Permanent delete is explicit, confirmed, and delegated to Android's
 * consent flow — the item is only forgotten after a consented success, and a trashed item is never
 * implied to be gone from the device while only the app-level flag is set.
 */
@Composable
fun OrganizationScreen(
    viewModel: OrganizationViewModel,
    onBack: () -> Unit,
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing
    val deleteFailedMessage = stringResource(R.string.trash_delete_failed)

    // Rotation-surviving selection: the tile id, not the whole LocalMedia. Tapping the already
    // selected tile deselects (no sticky selection with no way to clear it but Back).
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val selected = items?.firstOrNull { it.id == selectedId }

    // The consent launcher's result arrives across Activity recreation, so the pending item is
    // saved as its id; success is reported by id alone.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val id = pendingDeleteId
        pendingDeleteId = null
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            viewModel.onDeleteConfirmed(id)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = spacing.xs, top = spacing.sm)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.albums_back))
                    }
                    Text(stringResource(titleRes(viewModel.kind)), style = MaterialTheme.typography.titleLarge)
                }

                // null means the id-set has not resolved yet — show loading, not a false "empty".
                val list = items
                when {
                    list == null -> LoadingState(modifier = Modifier.weight(1f))
                    list.isEmpty() -> EmptyState(
                        icon = emptyIcon(viewModel.kind),
                        title = stringResource(emptyTitleRes(viewModel.kind)),
                        description = stringResource(emptyDescRes(viewModel.kind)),
                        modifier = Modifier.weight(1f),
                    )
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = spacing.gridTileMinSize),
                            contentPadding = PaddingValues(spacing.gridGutter),
                            horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
                            verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            items(list, key = { it.id }) { media ->
                                val isSelected = selectedId == media.id
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(spacing.gridItemRadius))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        .clickable { selectedId = if (isSelected) null else media.id },
                                ) {
                                    AsyncImage(
                                        model = media.contentUri,
                                        contentDescription = stringResource(
                                            R.string.organization_item_description,
                                            media.displayName ?: media.id.toString(),
                                        ),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                            }
                        }

                        selected?.let { media ->
                            ActionRow(
                                kind = viewModel.kind,
                                onOpen = { onOpenMedia(media.id) },
                                onUndo = { viewModel.undo(media.id); selectedId = null },
                                onDelete = { showDeleteConfirm = true },
                            )
                        }
                    }
                }
            }

            val messageText = if (message is OrgMessage.DeleteFailed) deleteFailedMessage else null
            messageText?.let {
                TransientMessageBar(
                    message = it,
                    onDismiss = viewModel::consumeMessage,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showDeleteConfirm) {
        val media = selected
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.trash_delete_title)) },
            text = { Text(stringResource(R.string.trash_delete_body)) },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    if (media != null) {
                        // Deletion is async + consent-gated; the result is handled by the launcher.
                        scope.launch {
                            when (val outcome = viewModel.requestDeleteForever(media)) {
                                is DeleteRequest.NeedsConsent -> {
                                    pendingDeleteId = media.id
                                    consentLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                                }
                                DeleteRequest.Deleted -> viewModel.onDeleteConfirmed(media.id)
                                DeleteRequest.Failed -> viewModel.reportDeleteFailed()
                            }
                        }
                    }
                    selectedId = null
                }) { Text(stringResource(R.string.trash_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.organization_cancel)) } },
        )
    }
}

@Composable
private fun ActionRow(kind: OrganizationKind, onOpen: () -> Unit, onUndo: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(titleRes(kind)), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onUndo) { Text(stringResource(undoLabelRes(kind))) }
        if (kind == OrganizationKind.TRASH) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.organization_delete_forever)) }
        }
        Button(onClick = onOpen) { Text(stringResource(R.string.map_selected_open)) }
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
