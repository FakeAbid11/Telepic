package com.telepix.ui.screens.photos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.telepix.R
import com.telepix.domain.media.LocalMedia
import com.telepix.permissions.MediaPermissionState
import com.telepix.permissions.rememberMediaPermissionState
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ErrorState
import com.telepix.ui.components.LoadingState
import com.telepix.ui.components.ScreenHeader
import com.telepix.ui.components.StateAction
import com.telepix.ui.theme.TelepixTokens

/**
 * The real local library screen (Phase 3).
 *
 * Reuses the Phase 2 permission model to decide whether to query MediaStore, and renders the
 * paged, day-grouped grid via [MediaGrid]. Loading / empty / permission / error states each have
 * appropriate UI. In tests, [permissionStateOverride] replaces the live permission lookup.
 */
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
    permissionStateOverride: MediaPermissionState? = null,
) {
    val controller = rememberMediaPermissionState()
    val permissionState = permissionStateOverride ?: controller.state

    LaunchedEffect(permissionState) { viewModel.updatePermission(permissionState) }
    LaunchedEffect(permissionState.hasAccess) {
        if (permissionState.hasAccess) viewModel.refresh()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = TelepixTokens.colors.mediaBackdrop,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.photos_title),
                style = MaterialTheme.typography.displaySmall,
            )
            when {
                permissionState.hasAccess -> MediaRegion(viewModel, onMediaSelected)
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
    }
}

@Composable
private fun MediaRegion(
    viewModel: PhotosViewModel,
    onMediaSelected: (LocalMedia) -> Unit,
) {
    val paging = viewModel.media.collectAsLazyPagingItems()
    val refresh = paging.loadState.refresh

    when {
        refresh is LoadState.Loading && paging.itemCount == 0 -> LoadingState()
        refresh is LoadState.Error -> ErrorState(
            title = stringResource(R.string.photos_error_title),
            explanation = stringResource(R.string.photos_error_description),
            onRetry = { paging.retry() },
        )
        refresh is LoadState.NotLoading && paging.itemCount == 0 -> EmptyState(
            icon = Icons.Outlined.PhotoLibrary,
            title = stringResource(R.string.photos_no_media_title),
            description = stringResource(R.string.photos_no_media_description),
            primaryAction = StateAction(
                label = stringResource(R.string.photos_refresh),
                onClick = viewModel::refresh,
            ),
        )
        else -> MediaGrid(media = paging, onMediaSelected = onMediaSelected)
    }
}
