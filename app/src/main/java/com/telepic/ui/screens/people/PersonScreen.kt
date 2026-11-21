package com.telepic.ui.screens.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.telepic.R
import com.telepic.domain.face.FaceCrop
import com.telepic.domain.face.Person
import com.telepic.domain.face.PersonFace
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.FaceCropTransformation
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens

/**
 * One person's photos.
 *
 * The header is driven by the *observed* person, not by anything passed on the route: a rename on this
 * screen, or a rebuild in the background, must be reflected immediately, and a stale header is the most
 * visible way to get that wrong. The route carries only the id, for the same reason.
 *
 * Every tile crops to the face it was found at. Showing the whole photo would make a grid of one
 * person's face indistinguishable from the timeline — the reason to open this screen is to see *them*.
 */
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onBack: () -> Unit,
    onOpenFace: (PersonFace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val person by viewModel.person.collectAsStateWithLifecycle()
    val faces by viewModel.faces.collectAsStateWithLifecycle()
    val current = person

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // The face whose long-press menu is open, if any. Tracked by id rather than by the face itself so a
    // background emission (a rebuild, a cover change) closes the menu instead of leaving it anchored to
    // a tile that no longer exists.
    var actionsFor by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = current?.let { personName(it) } ?: stringResource(R.string.people_title),
            onBack = onBack,
            trailing = if (current == null) {
                null
            } else {
                {
                    PersonActions(
                        person = current,
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it },
                        onRename = { renaming = true },
                        onToggleHidden = { viewModel.setHidden(!current.isHidden) },
                    )
                }
            },
        )

        when {
            // Null means the row is gone: a rebuild retired this group while the screen was open.
            current == null && faces.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Face,
                title = stringResource(R.string.people_person_missing_title),
                description = stringResource(R.string.people_person_missing_body),
                primaryAction = StateAction(stringResource(R.string.nav_back), onBack),
            )
            faces.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Face,
                title = stringResource(R.string.people_person_empty_title),
                description = stringResource(R.string.people_person_empty_body),
            )
            else -> FaceGrid(
                faces = faces,
                coverFaceId = current?.coverFaceId,
                actionsFor = actionsFor,
                // A face tile is an unlabelled image, so without this a screen reader announces a
                // grid of identical "Button"s. Naming the person makes each one identifiable.
                tileLabel = current?.let { stringResource(R.string.people_face_tile, personName(it)) }
                    ?: stringResource(R.string.people_person_photos),
                onOpenFace = onOpenFace,
                onOpenActions = { faceId -> actionsFor = faceId },
                onDismissActions = { actionsFor = null },
                onSetCover = { faceId ->
                    actionsFor = null
                    viewModel.setCover(faceId)
                },
            )
        }
    }

    if (renaming) {
        RenamePersonDialog(
            initialName = current?.name.orEmpty(),
            canClear = current?.name != null,
            onConfirm = { name ->
                viewModel.rename(name)
                renaming = false
            },
            onClear = {
                viewModel.rename(null)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun PersonActions(
    person: Person,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.album_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_rename_title)) },
                onClick = {
                    onExpandedChange(false)
                    onRename()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (person.isHidden) R.string.people_unhide else R.string.people_hide,
                        ),
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onToggleHidden()
                },
            )
        }
    }
}

@Composable
private fun FaceGrid(
    faces: List<PersonFace>,
    coverFaceId: Long?,
    actionsFor: Long?,
    tileLabel: String,
    onOpenFace: (PersonFace) -> Unit,
    onOpenActions: (Long) -> Unit,
    onDismissActions: () -> Unit,
    onSetCover: (Long) -> Unit,
) {
    val spacing = TelepicTokens.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = spacing.gridTileMinSize),
        contentPadding = PaddingValues(spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
        horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
    ) {
        items(faces, key = { it.faceId }) { face ->
            FaceTile(
                face = face,
                label = tileLabel,
                isCover = face.faceId == coverFaceId,
                actionsOpen = actionsFor == face.faceId,
                onClick = { onOpenFace(face) },
                onLongClick = { onOpenActions(face.faceId) },
                onDismissActions = onDismissActions,
                onSetCover = { onSetCover(face.faceId) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FaceTile(
    face: PersonFace,
    label: String,
    isCover: Boolean,
    actionsOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissActions: () -> Unit,
    onSetCover: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    val context = LocalContext.current
    val request = remember(face.faceId, face.contentUri) {
        ImageRequest.Builder(context)
            .data(face.contentUri)
            // Requests the crop's own source size rather than the full-resolution photo: a 12 MP
            // decode per tile is what makes a face grid scroll badly.
            .size(spacing.gridTileMinSize.value.toInt() * 2)
            .transformations(
                FaceCropTransformation(
                    FaceCrop(
                        faceId = face.faceId,
                        localMediaId = face.localMediaId,
                        contentUri = face.contentUri,
                        left = face.left,
                        top = face.top,
                        right = face.right,
                        bottom = face.bottom,
                    ),
                ),
            )
            .crossfade(true)
            .build()
    }

    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(spacing.gridItemRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // `mergeDescendants = true` so the description lands on the **actionable** node.
                // `combinedClickable` publishes its click action on a node of its own, and a
                // `semantics { }` placed before it becomes that node's *parent* — which split the tile
                // into two accessibility stops: the label, then an unlabelled "Button".
                .semantics(mergeDescendants = true) { contentDescription = label }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = stringResource(R.string.people_face_actions),
                    role = Role.Button,
                ),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isCover) {
                // A small, non-interactive mark rather than a border: the cover is worth knowing, but
                // it must not compete with the faces for attention.
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = stringResource(R.string.people_cover_badge),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(spacing.xs)
                        .size(spacing.lg),
                )
            }
        }
        DropdownMenu(expanded = actionsOpen, onDismissRequest = onDismissActions) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_set_cover)) },
                onClick = onSetCover,
                enabled = !isCover,
            )
        }
    }
}

@Composable
private fun RenamePersonDialog(
    initialName: String,
    canClear: Boolean,
    onConfirm: (String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.people_rename_hint)) },
                )
                // Only offered when there is a name to clear. A "clear" button on an unnamed person
                // would be an action with nothing to do, and it is not the same as cancelling.
                if (canClear) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.people_rename_clear))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.ifBlank { null }) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
