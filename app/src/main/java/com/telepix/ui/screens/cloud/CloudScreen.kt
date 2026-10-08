package com.telepix.ui.screens.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepix.R
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ScreenHeader

/**
 * Phase 1 Cloud foundation.
 *
 * Communicates that Cloud will hold Telegram-backed media, without implying any Telegram
 * connection exists yet. No TDLib in this phase.
 */
@Composable
fun CloudScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.cloud_title))
            EmptyState(
                icon = Icons.Outlined.CloudQueue,
                title = stringResource(R.string.cloud_empty_title),
                description = stringResource(R.string.cloud_empty_description),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
