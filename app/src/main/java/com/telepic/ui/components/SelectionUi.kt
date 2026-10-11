package com.telepic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
 * lives only in the top bar to avoid a duplicated number. [busy] disables the backup action and
 * shows a spinner while a bulk enqueue is in flight, so the screen never looks frozen. */
@Composable
fun SelectionActionBar(
    canBackup: Boolean,
    onBackup: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm, Alignment.End),
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.photos_selection_cancel))
            }
            TextButton(onClick = onBackup, enabled = canBackup && !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.photos_selection_backup),
                        modifier = Modifier.padding(start = TelepicTokens.spacing.xs),
                    )
                }
            }
        }
    }
}
