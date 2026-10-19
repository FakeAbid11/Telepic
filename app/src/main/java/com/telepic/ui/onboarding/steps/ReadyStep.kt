package com.telepic.ui.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.onboarding.BackupPreference
import com.telepic.permissions.MediaPermissionState
import com.telepic.telegram.TelegramAuthState
import com.telepic.ui.theme.TelepicTokens

/**
 * Screen 6 — Ready. Summarizes the *real* state: photo access reflects Android's permission,
 * Telegram shows connected only if truly authorized (never in Phase 2), and backup mirrors the
 * persisted preference. No fabricated status.
 */
@Composable
fun ReadyStep(
    permissionState: MediaPermissionState,
    telegramState: TelegramAuthState,
    backupPreference: BackupPreference?,
    modifier: Modifier = Modifier,
    setupFailed: Boolean = false,
    /**
     * The completion write failed — a different problem from a failed backup choice, and it needs
     * different words.
     */
    completionFailed: Boolean = false,
) {
    val spacing = TelepicTokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenMargin, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Column {
            Text(
                text = stringResource(R.string.onboarding_ready_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_ready_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        val photoOk = permissionState.hasAccess
        val photoValueRes = when (permissionState) {
            MediaPermissionState.Granted -> R.string.onboarding_ready_value_full
            MediaPermissionState.Partial -> R.string.onboarding_ready_value_limited
            else -> R.string.onboarding_ready_value_not_granted
        }
        SummaryRow(R.string.onboarding_ready_photo_access, photoValueRes, photoOk)

        val telegramAuthorized = telegramState is TelegramAuthState.Authorized
        val telegramValueRes = if (telegramAuthorized) {
            R.string.onboarding_ready_value_connected
        } else {
            R.string.onboarding_ready_value_not_connected
        }
        SummaryRow(R.string.onboarding_ready_telegram, telegramValueRes, telegramAuthorized)

        // BACKUP_ALL and SELECT_FOLDER (with a confirmed pick) both correspond to real, wired backup.
        // The value states the *intent* ("will back up"), never a completed backup the engine hasn't
        // confirmed. Values stay noun-short: this column sits beside a weighted label in a single row,
        // so a full sentence here squeezed the very label it was meant to summarize.
        val backupConfigured = backupPreference == BackupPreference.BACKUP_ALL ||
            backupPreference == BackupPreference.SELECT_FOLDER
        val backupValueRes = when (backupPreference) {
            BackupPreference.BACKUP_ALL -> R.string.onboarding_ready_value_backup_all
            BackupPreference.SELECT_FOLDER -> R.string.onboarding_ready_value_backup_folder
            else -> R.string.onboarding_ready_value_backup_none
        }
        SummaryRow(R.string.onboarding_ready_backup, backupValueRes, backupConfigured)

        // A failed persist used to be invisible on this last step: tapping "Start using Telepic" did
        // nothing, the flow never advanced, and there was no explanation — the exact case step 4
        // already reports. Same copy, same rule: never leave a dead button unexplained.
        //
        // The two failures get different words. A failed *completion* is fixed by pressing this
        // button again; a failed backup choice is fixed by choosing again. Answering a failed
        // completion with the backup copy pointed the user at the wrong control.
        if (completionFailed) {
            Text(
                text = stringResource(R.string.onboarding_completion_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (setupFailed) {
            Text(
                text = stringResource(R.string.onboarding_backup_setup_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SummaryRow(labelRes: Int, valueRes: Int, ok: Boolean) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                tint = if (ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(valueRes),
            style = MaterialTheme.typography.titleSmall,
            color = if (ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
