package com.telepic.ui.screens.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberForcedMediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.MediaGridSkeleton
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SelectAllAction
import com.telepic.ui.components.SelectionActionBar
import com.telepic.ui.components.SelectionTopBar
import com.telepic.ui.components.SettingsAction
import com.telepic.ui.components.StateAction
import com.telepic.ui.components.TransientMessageBar
import com.telepic.ui.screens.albums.AlbumNameDialog
import com.telepic.ui.screens.albums.AlbumPickerDialog
import com.telepic.ui.theme.TelepicTokens

/**
 * The primary Telepic media experience (Phase 8): a polished, local-first Photos timeline with an
 * adaptive grid, day grouping, a date rail and backup indicators — plus a top bar with Settings.
 *
 * A pure presentation layer: it reads the paged stream and the backup-status snapshot from
 * [PhotosViewModel] and never queries Room, Telegram or the hasher itself. Permission guidance
 * reuses the Phase 2/3 model; the empty / loading / error / partial states stay distinct (§29).
 */
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    permissionStateOverride: MediaPermissionState? = null,
) {
    // Exactly one controller is built per composition: the real one while the state is live, an inert
    // snapshot when the caller forces it. A call site never flips its override mid-life, so the branch
    // (and the remember order inside it) stays stable across recompositions.
    val controller = if (permissionStateOverride != null) {
        rememberForcedMediaPermissionState(permissionStateOverride)
    } else {
        rememberMediaPermissionState()
    }
    val permissionState = controller.state
    val backupStates by viewModel.backupStates.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectionActive = selectedItems.isNotEmpty()
    val selectionReport by viewModel.selectionReport.collectAsStateWithLifecycle()
    val isBackingUp by viewModel.isBackingUp.collectAsStateWithLifecycle()
    val isSelectingAll by viewModel.isSelectingAll.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val customAlbums by viewModel.customAlbums.collectAsStateWithLifecycle()
    val isAddingToAlbum by viewModel.isAddingToAlbum.collectAsStateWithLifecycle()
    val albumReport by viewModel.albumReport.collectAsStateWithLifecycle()
    // Dialog visibility survives rotation, like every other dialog in the app.
    // Both dialogs act on the current selection, which lives in memory and resets on process death.
    // Saved, they re-opened over an empty selection: the picker showed "no albums" and the create
    // dialog would have made an album containing nothing.
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showNewAlbumForSelection by remember { mutableStateOf(false) }
    // Whether the search field is open. Separate from the term itself, which lives in the ViewModel:
    // closing the field clears the term, but an empty field is still an open field.
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    // The permission controller above is the single source of truth for permission state; the VM
    // deliberately does not mirror it.
    LaunchedEffect(permissionState.hasAccess) {
        if (permissionState.hasAccess) viewModel.refresh()
    }
    // Back exits selection first, before leaving the Photos screen.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }
    // Then back closes the search, before leaving the screen — and clearing the term with it, so the
    // timeline is never left silently filtered behind a closed field.
    BackHandler(enabled = searchOpen && !selectionActive) {
        searchOpen = false
        viewModel.clearSearch()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = TelepicTokens.colors.mediaBackdrop,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                when {
                    // Search replaces the title row rather than floating over the grid: the field is
                    // the screen's subject while it is open, and a title competing with it for the
                    // same band would just be a label for a list the user is no longer looking at.
                    searchOpen -> PhotosSearchField(
                        query = searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onClose = {
                            searchOpen = false
                            viewModel.clearSearch()
                        },
                    )
                    selectionActive -> {
                        SelectionTopBar(
                            count = selectedItems.size,
                            onCancel = viewModel::clearSelection,
                            // No total: this grid is paged, so the library's size is not known until
                            // the select-all read returns. The label therefore claims no number, and
                            // the live count in the header reports what the read actually found.
                            selectAll = SelectAllAction(
                                onSelectAll = viewModel::selectAll,
                                busy = isSelectingAll,
                            ),
                        )
                    }
                    else -> ScreenHeader(
                        title = stringResource(R.string.photos_title),
                        // Any composable, so the header can carry this screen's whole control row
                        // rather than being re-implemented to fit it (see ScreenHeader's KDoc).
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Offered only with media access: searching a library you have not
                                // granted access to can only ever return nothing.
                                if (permissionState.hasAccess) {
                                    IconButton(onClick = { searchOpen = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Search,
                                            contentDescription = stringResource(R.string.photos_search_open),
                                        )
                                    }
                                }
                                // The same gear every other top-level screen carries (see SettingsAction);
                                // it is the only way into Settings, which is no longer a bottom-bar tab.
                                SettingsAction(onClick = onOpenSettings)
                            }
                        },
                    )
                }

                // Partial access honesty (§31): shown as soon as limited access is in effect — it never
                // claims a full library. It lives in the column flow rather than floated over the grid,
                // so it reserves its own space instead of covering the first row of photos.
                if (permissionState == MediaPermissionState.Partial) {
                    PartialAccessBanner(
                        modifier = Modifier.padding(
                            horizontal = TelepicTokens.spacing.screenMargin,
                            vertical = TelepicTokens.spacing.sm,
                        ),
                    )
                }

                when {
                    permissionState.hasAccess -> MediaRegion(
                        viewModel = viewModel,
                        backupStates = backupStates,
                        onMediaSelected = onMediaSelected,
                        searchQuery = searchQuery,
                    )
                    permissionState == MediaPermissionState.PermanentlyDenied -> EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.photos_permission_blocked_title),
                        description = stringResource(R.string.photos_permission_blocked_description),
                        primaryAction = StateAction(
                            label = stringResource(R.string.photos_open_settings),
                            onClick = controller.openAppSettings,
                        ),
                    )
                    else -> EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.photos_permission_title),
                        description = stringResource(R.string.photos_permission_description),
                        primaryAction = StateAction(
                            label = stringResource(R.string.photos_allow_access),
                            onClick = controller.requestPermission,
                        ),
                    )
                }
            }

            // Contextual bulk-action bar for selection mode (§15), stacked under any transient
            // feedback so the two BottomCenter surfaces never draw on top of each other. "Back up"
            // routes through the existing engine (recognition + dedup + queue + schedule).
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                // Transient feedback after a bulk enqueue; auto-cleared. Honest split of new work vs
                // items the engine recognized as already backed up / in progress.
                selectionReport?.let { report ->
                    TransientMessageBar(
                        message = stringResource(
                            R.string.photos_selection_result,
                            report.summary.queued,
                            report.summary.alreadyCovered,
                        ),
                        onDismiss = viewModel::consumeSelectionMessage,
                        // Two identical summaries render the same sentence; the report's own id
                        // keeps the second one from inheriting the first one's timer.
                        messageId = report.id,
                    )
                }
                // Adding to an album leaves no other trace on screen — the sheet closes and the photos
                // look unchanged — so this bar is the only thing that tells the user it worked. It says
                // "already in" when nothing was new, rather than claiming an addition that did not happen.
                albumReport?.let { report ->
                    TransientMessageBar(
                        message = stringResource(
                            if (report.added > 0) {
                                R.string.album_added_message
                            } else {
                                R.string.album_already_message
                            },
                            report.albumName,
                        ),
                        onDismiss = viewModel::consumeAlbumMessage,
                        messageId = report.id,
                    )
                }
                if (selectionActive) {
                    SelectionActionBar(
                        canBackup = selectedItems.isNotEmpty(),
                        // Each action reports its own progress: a shared flag made Back up spin while
                        // an add-to-album ran, naming the wrong thing.
                        backupBusy = isBackingUp,
                        addToAlbumBusy = isAddingToAlbum,
                        onBackup = viewModel::backupSelected,
                        onCancel = viewModel::clearSelection,
                        onAddToAlbum = { showAlbumPicker = true },
                    )
                }
            }
        }
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(
            albums = customAlbums,
            onPick = { album ->
                showAlbumPicker = false
                viewModel.addSelectionToAlbum(album.id)
            },
            onCreateNew = {
                showAlbumPicker = false
                showNewAlbumForSelection = true
            },
            onDismiss = { showAlbumPicker = false },
        )
    }

    if (showNewAlbumForSelection) {
        AlbumNameDialog(
            title = stringResource(R.string.albums_create_title),
            confirmLabel = stringResource(R.string.albums_create_confirm),
            onConfirm = { name ->
                showNewAlbumForSelection = false
                viewModel.createAlbumAndAddSelection(name)
            },
            onDismiss = { showNewAlbumForSelection = false },
        )
    }
}

// SelectionTopBar and SelectionActionBar live in ui/components/SelectionUi.kt, shared with album contents.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaRegion(
    viewModel: PhotosViewModel,
    backupStates: Map<Long, MediaBackupVisualState>,
    onMediaSelected: (LocalMedia) -> Unit,
    searchQuery: String,
) {
    val paging = viewModel.media.collectAsLazyPagingItems()
    val refresh = paging.loadState.refresh
    val hasContent = paging.itemCount > 0
    val isInitialLoading = refresh is LoadState.Loading && !hasContent
    val refreshFailed = refresh is LoadState.Error
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val selectedIds = selectedItems.keys
    val selectionActive = selectedItems.isNotEmpty()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isInitialLoading -> MediaGridSkeleton(minCellSize = TelepicTokens.spacing.gridTileMinSize)
            // A failed *first* load used to fall through to the grid below: no message, no retry, and
            // a blank screen that said nothing about the library having failed to open. A refresh
            // failure *with* content keeps its own banner further down instead.
            refreshFailed && !hasContent -> ErrorState(
                title = stringResource(R.string.photos_error_title),
                explanation = stringResource(R.string.photos_error_description),
                onRetry = { paging.retry() },
                retryLabel = stringResource(R.string.common_retry),
            )
            refresh is LoadState.NotLoading && !hasContent -> if (searchQuery.isNotBlank()) {
                // A search that matches nothing is not an empty library, and saying so would be a
                // claim about the user's photos rather than about their query.
                EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.photos_search_empty_title),
                    description = stringResource(R.string.photos_search_empty_description, searchQuery),
                )
            } else {
                EmptyState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = stringResource(R.string.photos_no_media_title),
                    description = stringResource(R.string.photos_no_media_description),
                    primaryAction = StateAction(
                        label = stringResource(R.string.photos_refresh),
                        onClick = viewModel::refresh,
                    ),
                )
            }
            else -> PullToRefreshBox(
                isRefreshing = refresh is LoadState.Loading && hasContent && !selectionActive,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                MediaGrid(
                    media = paging,
                    backupStates = backupStates,
                    onMediaSelected = onMediaSelected,
                    selectedIds = selectedIds,
                    selectionActive = selectionActive,
                    favoriteIds = favoriteIds,
                    onToggleSelect = viewModel::toggleSelection,
                    onLongSelect = viewModel::beginSelection,
                )
            }
        }

        // A refresh failure keeps loaded photos and offers a retry (§48). Suppressed while a
        // selection is active so it never stacks under the selection action bar.
        if (refreshFailed && hasContent && !selectionActive) {
            RefreshErrorBanner(
                onRetry = { paging.retry() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(TelepicTokens.spacing.md),
            )
        }
    }
}

/**
 * The search field, shown in place of the title row.
 *
 * It opens focused: the user tapped a magnifier to get here, so making them tap again to raise the
 * keyboard would be asking them to repeat themselves. Closing clears the term rather than just hiding
 * the field — a timeline left silently filtered behind a closed field is how a user concludes their
 * photos are missing.
 */
@Composable
private fun PhotosSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.photos_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.photos_search_close),
                )
            }
        },
        // Filtering is live, so "Search" has nothing left to run — it dismisses the keyboard, which is
        // the only thing the key can usefully do here.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenMargin, vertical = spacing.sm)
            .focusRequester(focusRequester),
    )
}

@Composable
private fun PartialAccessBanner(modifier: Modifier = Modifier) {
    val spacing = TelepicTokens.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.photos_partial_access_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RefreshErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.photos_refresh_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.photos_retry))
            }
        }
    }
}
