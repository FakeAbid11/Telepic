package com.telepix.ui.screens.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepix.R
import com.telepix.domain.backup.BackupItem
import com.telepix.domain.backup.BackupQueueStats
import com.telepix.domain.backup.BackupState
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ScreenHeader
import com.telepix.ui.theme.TelepixTokens

/**
 * The Phase 6 Backup Center foundation: an honest queue dashboard — status, pending/uploading/
 * completed/failed counts, and retry/cancel per item. It reports what the engine actually knows and
 * adds no polish that belongs to later phases.
 */
@Composable
fun BackupCenterScreen(
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = TelepixTokens.spacing

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.backup_center_title), onBack = onBack)

            if (state.isEmpty) {
                EmptyState(
                    icon = Icons.Outlined.CloudUpload,
                    title = stringResource(R.string.backup_center_empty_title),
                    description = stringResource(R.string.backup_center_empty_body),
                    primaryAction = StateActionStartBackup(viewModel::startBackup),
                )
                return@Column
            }

            Text(
                text = stringResource(statusLineRes(state)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screenMargin),
            )

            StatsRow(stats = state.stats, modifier = Modifier.padding(horizontal = spacing.screenMargin, vertical = spacing.md))

            Button(
                onClick = viewModel::startBackup,
                modifier = Modifier
                    .padding(horizontal = spacing.screenMargin)
                    .fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_action_start))
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = spacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(state.items, key = { it.id }) { item ->
                    BackupItemRow(
                        item = item,
                        onRetry = { viewModel.retry(item.id) },
                        onCancel = { viewModel.cancel(item.id) },
                    )
                }
            }
        }
    }
}

private fun statusLineRes(state: BackupUiState): Int = when {
    !state.isAuthorized && state.hasWaitingForAuth -> R.string.backup_center_status_waiting
    state.hasWaitingForNetwork -> R.string.backup_center_status_offline
    else -> R.string.backup_center_status_ready
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(stats: BackupQueueStats, modifier: Modifier = Modifier) {
    // FlowRow so the four stats reflow (4-across on wide, 2×2 on narrow / large font) instead of
    // cramming into one fixed row with clipped labels.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TelepixTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(TelepixTokens.spacing.sm),
    ) {
        StatCard(R.string.backup_stat_queued, stats.queued, Modifier.fillMaxWidth(0.47f))
        StatCard(R.string.backup_stat_uploading, stats.uploading, Modifier.fillMaxWidth(0.47f))
        StatCard(R.string.backup_stat_completed, stats.completed, Modifier.fillMaxWidth(0.47f))
        StatCard(R.string.backup_stat_failed, stats.failed, Modifier.fillMaxWidth(0.47f))
    }
}

@Composable
private fun StatCard(labelRes: Int, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(TelepixTokens.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupItemRow(item: BackupItem, onRetry: () -> Unit, onCancel: () -> Unit) {
    val spacing = TelepixTokens.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.fileName ?: item.localMediaId, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(item.state.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.state == BackupState.FAILED) {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.backup_action_retry)) }
            }
            if (item.state.canCancel()) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(start = spacing.sm),
                ) {
                    Text(stringResource(R.string.backup_action_cancel))
                }
            }
        }
    }
}

private fun BackupState.labelRes(): Int = when (this) {
    BackupState.QUEUED -> R.string.backup_queue_state_queued
    BackupState.PREPARING -> R.string.backup_queue_state_preparing
    BackupState.UPLOADING -> R.string.backup_queue_state_uploading
    BackupState.BACKED_UP -> R.string.backup_queue_state_backed_up
    BackupState.FAILED -> R.string.backup_queue_state_failed
    BackupState.CANCELLED -> R.string.backup_queue_state_cancelled
    BackupState.WAITING_FOR_NETWORK -> R.string.backup_queue_state_waiting_network
    BackupState.WAITING_FOR_AUTH -> R.string.backup_queue_state_waiting_auth
    BackupState.NOT_BACKED_UP -> R.string.backup_queue_state_queued
}

private fun BackupState.canCancel(): Boolean =
    this == BackupState.QUEUED || this == BackupState.WAITING_FOR_NETWORK || this == BackupState.WAITING_FOR_AUTH

@Composable
private fun StateActionStartBackup(onClick: () -> Unit) =
    com.telepix.ui.components.StateAction(stringResource(R.string.backup_action_start), onClick)
