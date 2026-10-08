package com.telepix.ui.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.telepix.R
import com.telepix.onboarding.BackupPreference
import com.telepix.ui.onboarding.components.OnboardingChoiceCard
import com.telepix.ui.theme.TelepixTokens

/** Stable test tags for the backup choice cards. */
const val TAG_BACKUP_ALL = "backup_all"
const val TAG_BACKUP_FOLDER = "backup_folder"
const val TAG_BACKUP_NOT_NOW = "backup_not_now"

/**
 * Screen 5 — Backup Preferences. Exactly the three PRD choices, single-select, persisted via
 * the host. Only the preference is recorded — no scanning or backup is triggered in this phase.
 */
@Composable
fun BackupPreferencesStep(
    selected: BackupPreference?,
    onSelect: (BackupPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepixTokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenMargin, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column {
            Text(
                text = stringResource(R.string.onboarding_backup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_backup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        OnboardingChoiceCard(
            selected = selected == BackupPreference.BACKUP_ALL,
            onClick = { onSelect(BackupPreference.BACKUP_ALL) },
            icon = Icons.Outlined.PhotoLibrary,
            title = stringResource(R.string.onboarding_backup_all_title),
            body = stringResource(R.string.onboarding_backup_all_body),
            modifier = Modifier.testTag(TAG_BACKUP_ALL),
        )
        OnboardingChoiceCard(
            selected = selected == BackupPreference.SELECT_FOLDER,
            onClick = { onSelect(BackupPreference.SELECT_FOLDER) },
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.onboarding_backup_folder_title),
            body = stringResource(R.string.onboarding_backup_folder_body),
            modifier = Modifier.testTag(TAG_BACKUP_FOLDER),
        )
        OnboardingChoiceCard(
            selected = selected == BackupPreference.NOT_NOW,
            onClick = { onSelect(BackupPreference.NOT_NOW) },
            icon = Icons.Outlined.CloudDownload,
            title = stringResource(R.string.onboarding_backup_not_now_title),
            body = stringResource(R.string.onboarding_backup_not_now_body),
            modifier = Modifier.testTag(TAG_BACKUP_NOT_NOW),
        )

        if (selected == BackupPreference.SELECT_FOLDER) {
            Text(
                text = stringResource(R.string.onboarding_backup_folder_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}
