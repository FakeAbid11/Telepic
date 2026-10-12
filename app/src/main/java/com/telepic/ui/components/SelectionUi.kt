package com.telepic.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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

/** The selection-mode header: a close action and the live selected count in place of the title. */
@Composable
fun SelectionTopBar(count: Int, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        )
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
 * pass at most one, which is what keeps the bar inside a phone's width. */
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
) {
    val anyBusy = backupBusy || addToAlbumBusy || removeBusy

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
        }
    }
}

/**
 * A selection-bar button's leading glyph: a spinner while *its own* action runs, otherwise its icon.
 * Both are 18dp, so the button does not change width when it starts working.
 */
@Composable
private fun ActionGlyph(busy: Boolean, icon: ImageVector) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
