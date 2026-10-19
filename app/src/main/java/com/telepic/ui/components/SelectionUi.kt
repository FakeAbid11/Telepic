package com.telepic.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.data.backup.BulkBackupSummary
import com.telepic.ui.theme.TelepicTokens

/**
 * Shared selection chrome used by every paged media grid (Photos timeline and album contents),
 * so entering selection mode looks and behaves identically everywhere.
 */

/**
 * A transient bulk-backup outcome for the message bar. [id] is unique per report so two
 * identical summaries still re-announce (a bare StateFlow of the summary would conflate them).
 */
data class BulkBackupReport(val summary: BulkBackupSummary, val id: Long)

/**
 * An optional "select all" action for [SelectionTopBar].
 *
 * [total] is how many items the action will select, when the caller already knows: the label shows it
 * ("Select all (240)") so the scope of the action is never implied. It is null when the caller does
 * not know yet — a paged grid has to read the rest of the library before it can promise a number —
 * and the label then claims no number at all rather than guessing one. [busy] puts a spinner in the
 * leading glyph while that read runs, keeping the label where the user can still read it.
 */
data class SelectAllAction(
    val onSelectAll: () -> Unit,
    val busy: Boolean = false,
    val total: Int? = null,
)

/** The selection-mode header: a close action, the live selected count, and optional "select all". */
@Composable
fun SelectionTopBar(
    count: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    selectAll: SelectAllAction? = null,
) {
    // The header scrolls horizontally, like the action bar below.
    //
    // It has the same shape — a 48dp icon button, a label and a text action — and the same failure
    // mode: a plain Row neither wraps nor scrolls, so at a large display font the "Select all" button
    // was pushed past the right edge and simply could not be reached.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                start = TelepicTokens.spacing.xs,
                end = TelepicTokens.spacing.screenMargin,
                top = TelepicTokens.spacing.lg,
                bottom = TelepicTokens.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.photos_selection_cancel),
            )
        }
        Text(
            text = stringResource(R.string.photos_selection_count, count),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            // Not weighted: a scrollable Row measures its content with an unbounded width, and a
            // weighted child would then be handed that unbounded space. The count is short, so a
            // fixed gap reads the same and keeps the layout predictable.
            modifier = Modifier.padding(end = TelepicTokens.spacing.sm),
        )
        // Hidden once the count already covers the known total: an action that would select nothing
        // new is a dead control, and it is only knowable when the caller passed a total.
        if (selectAll != null && (selectAll.total == null || selectAll.total > count)) {
            TextButton(onClick = selectAll.onSelectAll, enabled = !selectAll.busy) {
                ActionGlyph(busy = selectAll.busy, icon = Icons.Outlined.SelectAll)
                val total = selectAll.total
                Text(
                    text = if (total != null) {
                        stringResource(R.string.photos_selection_select_all_count, total)
                    } else {
                        stringResource(R.string.photos_selection_select_all)
                    },
                    modifier = Modifier.padding(start = TelepicTokens.spacing.xs),
                )
            }
        }
    }
}

/** The bottom bulk-action bar shown while selecting: a Back up button and Cancel. The selected count
 * lives only in the top bar to avoid a duplicated number.
 *
 * Each action carries **its own** busy flag, and only the action that is actually running shows the
 * spinner. A single shared flag made the *Back up* button animate while an add-to-album or a removal
 * was in flight — the one control on screen that names what it does was naming the wrong thing. While
 * any action runs, the others are disabled so a second one cannot be started on top of it.
 *
 * [onAddToAlbum] and [onRemoveFromAlbum] are optional because they only apply where membership is the
 * user's to change: adding from the timeline, removing from a user-created album. A folder album passes
 * neither — its contents are the filesystem's, so there is nothing to add to or remove from. Callers
 * pass at most one, which is what keeps the bar inside a phone's width.
 *
 * [onFavorite] and [onTrash] are optional for the same reason, and they live behind an **overflow**
 * rather than as two more text buttons. The bar is already a scrolling row — two or three text
 * actions come to roughly 335dp, which fits a 360dp phone but not a 320dp one — and two more text
 * buttons would push the app's headline action ("Back up", last in the row) off the screen first. A
 * single 48dp overflow costs one icon instead of ~230dp of text, and it is the idiom the Viewer's own
 * action cluster already uses.
 */
@Composable
fun SelectionActionBar(
    canBackup: Boolean,
    onBackup: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    backupBusy: Boolean = false,
    addToAlbumBusy: Boolean = false,
    removeBusy: Boolean = false,
    onAddToAlbum: (() -> Unit)? = null,
    onRemoveFromAlbum: (() -> Unit)? = null,
    favoriteBusy: Boolean = false,
    trashBusy: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
) {
    val anyBusy = backupBusy || addToAlbumBusy || removeBusy || favoriteBusy || trashBusy
    val hasOverflow = onFavorite != null || onTrash != null
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm)
                // Scrollable rather than clipped. Two or three text actions come to roughly 335dp, which
                // fits a 360dp phone but not a 320dp one, and not a 360dp one at a large display font —
                // and a plain Row neither wraps nor scrolls, so an action simply became unreachable. The
                // padding sits outside the scroll so the gutters stay put while the content moves.
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm, Alignment.End),
        ) {
            // Cancel stays enabled throughout: a write that is running is not a reason to trap the user
            // in selection mode, and the running action reports its own outcome when it returns.
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.photos_selection_cancel))
            }
            onAddToAlbum?.let { add ->
                TextButton(onClick = add, enabled = !anyBusy) {
                    ActionGlyph(busy = addToAlbumBusy, icon = Icons.Outlined.Add)
                    Text(
                        text = stringResource(R.string.photos_selection_add_to_album),
                        modifier = Modifier.padding(start = TelepicTokens.spacing.xs),
                    )
                }
            }
            onRemoveFromAlbum?.let { remove ->
                // No icon: the label is the whole message, and a trash can here would suggest the photo
                // is being deleted rather than just taken out of this album.
                TextButton(onClick = remove, enabled = !anyBusy) {
                    if (removeBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(TelepicTokens.spacing.xs))
                    }
                    Text(stringResource(R.string.photos_selection_remove_from_album))
                }
            }
            TextButton(onClick = onBackup, enabled = canBackup && !anyBusy) {
                // The label stays put while busy. Swapping it for a bare spinner made the button's
                // own action unreadable at exactly the moment the user needed to know what was
                // running; only the leading icon changes, so the button also keeps its width.
                ActionGlyph(busy = backupBusy, icon = Icons.Outlined.CloudUpload)
                Text(
                    text = stringResource(R.string.photos_selection_backup),
                    modifier = Modifier.padding(start = TelepicTokens.spacing.xs),
                )
            }
            if (hasOverflow) {
                // The organisation actions, behind one icon. The glyph spins while either of them is
                // running, so the bar never looks idle while a write is in flight.
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !anyBusy) {
                        ActionGlyph(
                            busy = favoriteBusy || trashBusy,
                            icon = Icons.Outlined.MoreVert,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        onFavorite?.let { favorite ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.photos_selection_favorite)) },
                                onClick = {
                                    menuOpen = false
                                    favorite()
                                },
                                leadingIcon = {
                                    ActionGlyph(busy = favoriteBusy, icon = Icons.Outlined.StarBorder)
                                },
                            )
                        }
                        onTrash?.let { trash ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.photos_selection_trash)) },
                                onClick = {
                                    menuOpen = false
                                    trash()
                                },
                                leadingIcon = {
                                    ActionGlyph(busy = trashBusy, icon = Icons.Outlined.Delete)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A selection-bar button's leading glyph: a spinner while *its own* action runs, otherwise its icon.
 *
 * Both are the same size, so the button does not change width when it starts working. [size] is
 * overridable because the overflow's own icon is the standard 24dp rather than a button's 18dp
 * leading glyph.
 */
@Composable
private fun ActionGlyph(busy: Boolean, icon: ImageVector, size: Dp = 18.dp) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 2.dp)
    } else {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(size))
    }
}
