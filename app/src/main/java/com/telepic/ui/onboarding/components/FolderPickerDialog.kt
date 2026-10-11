package com.telepic.ui.onboarding.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.telepic.R
import com.telepic.domain.media.Album
import com.telepic.ui.theme.TelepicTokens

/** Stable test tags for the folder picker. */
const val TAG_FOLDER_PICKER_CONFIRM = "folder_picker_confirm"
const val TAG_FOLDER_PICKER_EMPTY = "folder_picker_empty"

/**
 * Multi-select sheet over the device's media folders (MediaStore buckets). Identity is the stable
 * `bucketId` — never the name — matching everything else in the backup pipeline. Confirm is only
 * offered with at least one folder: "only these folders" with none chosen backs up nothing, so the
 * dialog never writes an empty SELECT_FOLDER state (the coordinator treats that as NOT_NOW anyway).
 */
@Composable
fun FolderPickerDialog(
    folders: List<Album>,
    initiallySelected: Set<Long>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false,
) {
    // Rotation-surviving: the in-progress selection is user input, not seed data.
    var selected by rememberSaveable(
        stateSaver = listSaver(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(initiallySelected) }
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
                folders.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.onboarding_folder_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TAG_FOLDER_PICKER_EMPTY),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
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
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.albums_item_count, album.count),
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
