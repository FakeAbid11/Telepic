package com.telepic.ui.onboarding.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.telepic.R
import com.telepic.domain.media.Album
import com.telepic.ui.theme.TelepicTokens

/** Stable test tags for the folder picker. */
const val TAG_FOLDER_PICKER_CONFIRM = "folder_picker_confirm"
const val TAG_FOLDER_PICKER_EMPTY = "folder_picker_empty"
const val TAG_FOLDER_PICKER_ERROR = "folder_picker_error"

/**
 * Multi-select sheet over the device's media folders (MediaStore buckets). Identity is the stable
 * `bucketId` — never the name — matching everything else in the backup pipeline. Confirm is only
 * offered with at least one folder: "only these folders" with none chosen backs up nothing, so the
 * dialog never writes an empty SELECT_FOLDER state (the coordinator treats that as NOT_NOW anyway).
 *
 * A bucket's name is only its last path segment, so `DCIM/Camera` and `Pictures/Camera` are two
 * rows both reading "Camera" — indistinguishable, and therefore not really a choice. Where that
 * happens the folder's path is shown as a second line so the user can tell them apart; where it does
 * not, the row stays one line, because a path on every row is noise that pushes the count off screen.
 *
 * [folders] must come from the *backup* scope (`AppContainer.backupAlbumRepository`), not the
 * display one: a folder whose media the timeline currently hides would otherwise be missing from
 * this list entirely, and its count would disagree with what the backup pass actually discovers.
 */
@Composable
fun FolderPickerDialog(
    folders: List<Album>,
    initiallySelected: Set<Long>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false,
    /** The bucket read failed — different from the device having no folders, and retryable. */
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    // Rotation-surviving: the in-progress selection is user input, not seed data.
    var selected by rememberSaveable(
        stateSaver = listSaver(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(initiallySelected) }
    // Titles shared by more than one folder — exactly the rows that cannot be told apart by name.
    // Derived from the list rather than stored, so it stays correct if the folders reload.
    val ambiguousTitles = remember(folders) {
        folders.groupingBy { it.title }.eachCount().filterValues { it > 1 }.keys
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.onboarding_folder_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            when {
                // While the bucket query is still in flight, show loading — not the empty text,
                // which would blame permissions for a list that simply hasn't arrived yet.
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(TelepicTokens.spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                // Checked before "empty": a failed read also yields no rows, and saying "no folders
                // found" here blamed photo access for a provider error while offering no way out.
                failed -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.folder_picker_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TAG_FOLDER_PICKER_ERROR),
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
                folders.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.onboarding_folder_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TAG_FOLDER_PICKER_EMPTY),
                    )
                }
                else -> {
                    // Bounded to the shared dialog-body token, like the album picker and the
                    // licence list. Without it this list grew to fill the whole screen on a device
                    // with many MediaStore buckets, while the two sibling dialogs stayed at 400dp —
                    // the same kind of body, three different heights.
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = TelepicTokens.spacing.dialogMaxHeight),
                    ) {
                        items(folders, key = { it.bucketId }) { album ->
                            val isChecked = album.bucketId in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // The row is the single toggle, so the album name, its count and
                                    // its checked state are announced together. A plain clickable Row
                                    // left the Checkbox as a second focus stop that announced
                                    // "checked" with nothing to say what was checked.
                                    .toggleable(
                                        value = isChecked,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            selected = if (checked) {
                                                selected + album.bucketId
                                            } else {
                                                selected - album.bucketId
                                            }
                                        },
                                    )
                                    .padding(vertical = TelepicTokens.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm),
                            ) {
                                // onCheckedChange = null: the row owns the toggle, so the checkbox
                                // must not be a separately focusable control of its own.
                                Checkbox(checked = isChecked, onCheckedChange = null)
                                // The name and its optional path are one column so the count keeps
                                // its slot on the right regardless of how long the path is.
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // Only for a name shared by two folders, and only when the
                                    // provider gave us a path to show (API 29+).
                                    val path = album.relativePath
                                    if (path != null && album.title in ambiguousTitles) {
                                        Text(
                                            text = path,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Text(
                                    text = pluralStringResource(R.plurals.albums_item_count, album.count, album.count),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.testTag(TAG_FOLDER_PICKER_CONFIRM),
            ) {
                Text(stringResource(R.string.onboarding_folder_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.organization_cancel))
            }
        },
    )
}
