package com.telepix.ui.screens.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepix.R
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ScreenHeader

/**
 * Phase 1 Map foundation.
 *
 * A clean placeholder indicating location-based browsing. No OpenStreetMap and no tile
 * downloads in this phase.
 */
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.map_title))
            EmptyState(
                icon = Icons.Outlined.Map,
                title = stringResource(R.string.map_empty_title),
                description = stringResource(R.string.map_empty_description),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
