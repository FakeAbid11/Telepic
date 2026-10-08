package com.telepix.ui.screens.albums

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepix.R
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ScreenHeader

/**
 * Phase 1 Albums foundation.
 *
 * Establishes the visual home for system and user albums. No album scanning in this phase.
 */
@Composable
fun AlbumsScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.albums_title))
            EmptyState(
                icon = Icons.Outlined.Collections,
                title = stringResource(R.string.albums_empty_title),
                description = stringResource(R.string.albums_empty_description),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
