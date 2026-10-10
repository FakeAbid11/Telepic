package com.telepic.ui.onboarding.steps

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.telepic.R
import com.telepic.domain.media.Album
import com.telepic.onboarding.BackupPreference
import com.telepic.ui.onboarding.components.FolderPickerDialog
import com.telepic.ui.onboarding.components.OnboardingChoiceCard
import com.telepic.ui.theme.TelepicTokens

/** Stable test tags for the backup choice cards. */
const val TAG_BACKUP_ALL = "backup_all"
const val TAG_BACKUP_FOLDER = "backup_folder"
const val TAG_BACKUP_NOT_NOW = "backup_not_now"

/**
 * Screen 5 — Backup Preferences. Exactly the three PRD choices, single-select, persisted via the
 * host. Selecting "a folder" opens the real bucket picker; the choice is only saved once the user
 * confirms at least one folder (identity = stable MediaStore bucketId, enforced by the coordinator's
 * per-bucket scan). BACKUP_ALL connects to the real backup initialization and NOT_NOW starts nothing.
 */
@Composable
fun BackupPreferencesStep(
    selected: BackupPreference?,
    selectedBucketIds: Set<Long>,
    folders: List<Album>,
    onSelect: (BackupPreference) -> Unit,
    onNeedFolders: () -> Unit,
    onPickFolders: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepicTokens.spacing
    var showPicker by remember { mutableStateOf(false) }
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
            onClick = {
                // Open the picker against the live folder list; nothing is persisted until confirm.
                onNeedFolders()
                showPicker = true
            },
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.onboarding_backup_folder_title),
            body = if (selected == BackupPreference.SELECT_FOLDER && selectedBucketIds.isNotEmpty()) {
                stringResource(R.string.onboarding_backup_folder_selected, selectedBucketIds.size)
            } else {
                stringResource(R.string.onboarding_backup_folder_body)
            },
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
    }

    if (showPicker) {
        FolderPickerDialog(
            folders = folders,
            initiallySelected = selectedBucketIds,
            onConfirm = { ids ->
                showPicker = false
                onPickFolders(ids)
            },
            onDismiss = { showPicker = false },
        )
    }
}
