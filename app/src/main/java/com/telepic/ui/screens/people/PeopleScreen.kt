package com.telepic.ui.screens.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.telepic.R
import com.telepic.data.face.FaceSimilarityPreset
import com.telepic.domain.face.PeopleState
import com.telepic.domain.face.Person
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.FaceCropTransformation
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SettingsAction
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens

/**
 * The People tab: a grid of everyone Telepic has grouped, behind an opt-in for the models.
 *
 * The screen is a `when` over [PeopleState] rather than a set of flags, so the six things it can show
 * — the model offer, a download, a failed download, the scan offer, a running scan, the grid — cannot
 * overlap or be missed. Each branch is a whole screen, because each is a genuinely different answer to
 * "what should this tab be doing right now".
 */
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onOpenPerson: (Person) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preset by viewModel.preset.collectAsStateWithLifecycle()

    var groupingOpen by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.people_title),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only meaningful once there is something to regroup. Offering it against an empty
                    // index would be a control whose only effect is a progress spinner.
                    if (state is PeopleState.Ready) {
                        PeopleOverflowAction(
                            onGrouping = { groupingOpen = true },
                            onRemoveData = { confirmingRemove = true },
                        )
                    }
                    SettingsAction(onClick = onOpenSettings)
                }
            },
        )
        when (val current = state) {
            PeopleState.ModelsAbsent -> ModelsAbsentContent(onDownload = viewModel::downloadModels)
            is PeopleState.Downloading -> DownloadingContent()
            PeopleState.DownloadFailed -> DownloadFailedContent(onRetry = viewModel::downloadModels)
            PeopleState.NotIndexed -> NotIndexedContent(onStart = viewModel::startScanning)
            is PeopleState.Indexing -> IndexingContent(current)
            is PeopleState.Ready -> PeopleGrid(
                people = current.people,
                totalFaces = current.totalFaces,
                onOpenPerson = onOpenPerson,
                onScanAgain = viewModel::startScanning,
            )
        }
    }

    if (groupingOpen) {
        GroupingSettingsDialog(
            current = preset,
            onApply = { chosen ->
                groupingOpen = false
                viewModel.applyGrouping(chosen)
            },
            onDismiss = { groupingOpen = false },
        )
    }

    if (confirmingRemove) {
        RemoveFaceDataDialog(
            onConfirm = {
                confirmingRemove = false
                viewModel.removeAllFaceData()
            },
            onDismiss = { confirmingRemove = false },
        )
    }
}

/** The People tab's overflow: the two actions that are not about one person. */
@Composable
private fun PeopleOverflowAction(
    onGrouping: () -> Unit,
    onRemoveData: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.album_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_settings_title)) },
                onClick = {
                    expanded = false
                    onGrouping()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_remove_data)) },
                onClick = {
                    expanded = false
                    onRemoveData()
                },
            )
        }
    }
}

@Composable
private fun ModelsAbsentContent(onDownload: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.Face,
        title = stringResource(R.string.people_intro_title),
        description = stringResource(R.string.people_intro_body) + "\n\n" +
            stringResource(R.string.people_intro_models_size),
        primaryAction = StateAction(stringResource(R.string.people_download_models), onDownload),
    )
}

@Composable
private fun DownloadingContent() {
    val spacing = TelepicTokens.spacing
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.people_downloading),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = spacing.lg),
        )
        // Indeterminate: the download's own progress is reported through a foreground notification,
        // and duplicating it here would mean two sources for one number that could disagree.
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NotIndexedContent(onStart: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.People,
        title = stringResource(R.string.people_scan_title),
        description = stringResource(R.string.people_scan_body),
        primaryAction = StateAction(stringResource(R.string.people_start_scan), onStart),
    )
}

/**
 * The download failed.
 *
 * Retrying is the primary action rather than a secondary one: the overwhelmingly common cause is a
 * connection that dropped mid-transfer, and the retry resumes from a clean download of the same two
 * files. Nothing has been indexed yet, so there is no state to lose.
 */
@Composable
private fun DownloadFailedContent(onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.Face,
        title = stringResource(R.string.people_download_failed_title),
        description = stringResource(R.string.people_download_failed),
        primaryAction = StateAction(stringResource(R.string.common_retry), onRetry),
    )
}

/** Confirms the one People action that is not undoable. */
@Composable
private fun RemoveFaceDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_remove_data_title)) },
        text = { Text(stringResource(R.string.people_remove_data_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.people_remove_data_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun IndexingContent(state: PeopleState.Indexing) {
    val spacing = TelepicTokens.spacing
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.people_scanning_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = spacing.xl),
        )
        Text(
            text = stringResource(
                R.string.people_scanning_body,
                state.photosExamined,
                state.facesFound,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}

@Composable
private fun PeopleGrid(
    people: List<Person>,
    totalFaces: Int,
    onOpenPerson: (Person) -> Unit,
    onScanAgain: () -> Unit,
) {
    val spacing = TelepicTokens.spacing

    if (people.isEmpty()) {
        // Faces exist but no group is big enough. That is a different message from "nothing found",
        // and it is the one case where pointing at the grouping setting actually helps.
        EmptyState(
            icon = Icons.Outlined.People,
            title = stringResource(if (totalFaces == 0) R.string.people_no_faces_title else R.string.people_empty_title),
            description = stringResource(
                if (totalFaces == 0) R.string.people_no_faces_body else R.string.people_empty_body,
            ),
            primaryAction = StateAction(stringResource(R.string.people_rescan), onScanAgain),
        )
        return
    }

    LazyVerticalGrid(
        // A wider minimum than the media grid: a person card is a face plus a name, and packing them
        // as tightly as thumbnails made the faces too small to recognise at a glance.
        columns = GridCells.Adaptive(minSize = PERSON_TILE_MIN_SIZE),
        contentPadding = PaddingValues(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(people, key = { it.personId }) { person ->
            PersonTile(person = person, onClick = { onOpenPerson(person) })
        }
    }
}

/**
 * One person: their cover face in a circle, their name, and how many photos they appear in.
 *
 * The cover is cropped **to the face** rather than to the centre of the photo — see
 * [FaceCropTransformation]. A person photographed off-centre would otherwise be a card showing a wall,
 * and a group's whole purpose is to be recognisable at a glance.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonTile(
    person: Person,
    onClick: () -> Unit,
) {    val spacing = TelepicTokens.spacing
    val context = LocalContext.current
    val cover = person.cover
    val displayName = personName(person)
    val openLabel = stringResource(R.string.people_open_person, displayName)
    val coverRequest = remember(cover?.contentUri, cover?.faceId) {
        cover?.contentUri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .size(PERSON_TILE_MIN_SIZE.value.toInt() * 2)
                .transformations(FaceCropTransformation(cover))
                .crossfade(true)
                .build()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .combinedClickable(
                onClick = onClick,
                onClickLabel = openLabel,
                role = Role.Button,
            )
            .padding(vertical = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(PERSON_COVER_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (coverRequest != null) {
                AsyncImage(
                    model = coverRequest,
                    // The name below already announces this card, so the image is decorative.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No cover is a real state (a person whose cover photo was removed). A glyph is
                // honest about it; a placeholder photo would claim a face we do not have.
                Icon(
                    imageVector = Icons.Outlined.Face,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(spacing.xl),
                )
            }
        }
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Text(
            text = stringResource(R.string.people_face_count, person.faceCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The grouping-strictness picker, shown on the People tab's overflow.
 *
 * Presented as three explained choices rather than a slider because the numbers are meaningless to a
 * user: what they need to know is which way the mistake goes. The default is [FaceSimilarityPreset.STRICT]
 * and the copy says why — a split is undoable with a merge, and a merge is not.
 *
 * The choice is staged locally and applied once, because applying *is* a full rebuild. Rebuilding on
 * every radio tap would run the clusterer three times for someone comparing two settings, and the
 * screen has no way to cancel work already started.
 */
@Composable
fun GroupingSettingsDialog(
    current: FaceSimilarityPreset,
    onApply: (FaceSimilarityPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(current) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_settings_title)) },
        text = {
            Column {
                FaceSimilarityPreset.entries.forEach { preset ->
                    GroupingOption(
                        preset = preset,
                        selected = preset == selected,
                        onClick = { selected = preset },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(selected) },
                // Applying the grouping already in force would spend a rebuild to produce the same
                // groups.
                enabled = selected != current,
            ) {
                Text(stringResource(R.string.people_rebuild_grouping))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupingOption(    preset: FaceSimilarityPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, role = Role.RadioButton)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = spacing.sm)) {
            Text(text = preset.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = preset.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FaceSimilarityPreset.label(): String = when (this) {
    FaceSimilarityPreset.STRICT -> stringResource(R.string.people_settings_strict)
    FaceSimilarityPreset.BALANCED -> stringResource(R.string.people_settings_balanced)
    FaceSimilarityPreset.LOOSE -> stringResource(R.string.people_settings_loose)
}

@Composable
private fun FaceSimilarityPreset.description(): String = when (this) {
    FaceSimilarityPreset.STRICT -> stringResource(R.string.people_settings_strict_description)
    FaceSimilarityPreset.BALANCED -> stringResource(R.string.people_settings_balanced_description)
    FaceSimilarityPreset.LOOSE -> stringResource(R.string.people_settings_loose_description)
}

/** Larger than a media thumbnail: the cover has to be recognisable, and a face is a small part of it. */
private val PERSON_TILE_MIN_SIZE = 104.dp

private val PERSON_COVER_SIZE = 88.dp
