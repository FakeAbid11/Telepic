package com.telepix.ui.screens.photos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepix.R
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ScreenHeader
import com.telepix.ui.theme.TelepixTokens

/**
 * Phase 1 Photos foundation.
 *
 * The most visually important destination: it establishes the media-first surface and
 * clearly communicates that the local timeline will live here. No MediaStore scanning and
 * no fake media — that arrives in Phase 3.
 */
@Composable
fun PhotosScreen(modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Surface(
        modifier = modifier.fillMaxSize(),
        color = TelepixTokens.colors.mediaBackdrop,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.photos_title),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(R.string.nav_photos),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenMargin),
            )
            EmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.photos_empty_title),
                description = stringResource(R.string.photos_empty_description),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
