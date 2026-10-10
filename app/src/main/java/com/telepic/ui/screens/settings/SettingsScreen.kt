package com.telepic.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.BuildConfig
import com.telepic.R
import com.telepic.onboarding.BackupPreference
import com.telepic.permissions.rememberNotificationPermissionState
import com.telepic.settings.ThemeMode
import com.telepic.telegram.TelegramAuthState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.onboarding.components.FolderPickerDialog
import com.telepic.ui.theme.TelepicTokens

/** Human-readable label resource for a [ThemeMode]. */
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

/** Human-readable label resource for a backup preference choice. */
val BackupPreference.labelRes: Int
    get() = when (this) {
        BackupPreference.BACKUP_ALL -> R.string.onboarding_backup_all_title
        BackupPreference.SELECT_FOLDER -> R.string.onboarding_backup_folder_title
        BackupPreference.NOT_NOW -> R.string.onboarding_backup_not_now_title
    }

/**
 * Settings: the real Telegram account status (with sign-out), the backup preference editor (the same
 * persisted choice onboarding writes, applied immediately through the coordinator), the theme picker,
 * measured cache sizes with a safe staging-cache clear, and version info. Rows reflect behavior that
 * exists — nothing here reports a state the app cannot honor.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBackupCenter: () -> Unit = {},
) {
    val spacing = TelepicTokens.spacing
    val context = LocalContext.current
    val notifications = rememberNotificationPermissionState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val preference by viewModel.backupPreference.collectAsStateWithLifecycle()
    val bucketIds by viewModel.backupBucketIds.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val storage by viewModel.storageSummary.collectAsStateWithLifecycle()

    val accountSummary = when (val state = authState) {
        is TelegramAuthState.Authorized -> state.user.displayName
        else -> stringResource(R.string.settings_account_status_disconnected)
    }
    val backupSummary = when (preference) {
        BackupPreference.BACKUP_ALL -> stringResource(BackupPreference.BACKUP_ALL.labelRes)
        BackupPreference.SELECT_FOLDER ->
            stringResource(R.string.settings_backup_pref_folders, bucketIds.size)
        BackupPreference.NOT_NOW -> stringResource(BackupPreference.NOT_NOW.labelRes)
        null -> stringResource(R.string.settings_backup_pref_unset)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenHeader(title = stringResource(R.string.settings_title))

            SettingsSection(title = stringResource(R.string.settings_section_account)) {
                // Info row: enabled (not dimmed) with no onClick — a real state read, not a stub.
                SettingsRow(
                    title = stringResource(R.string.settings_account_telegram),
                    summary = accountSummary,
                )
                if (authState is TelegramAuthState.Authorized) {
                    SettingsRow(
                        title = stringResource(R.string.settings_account_sign_out),
                        enabled = true,
                        onClick = { showSignOutConfirm = true },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_backup)) {
                SettingsRow(
                    title = stringResource(R.string.backup_settings_entry),
                    summary = stringResource(R.string.backup_settings_entry_summary),
                    enabled = true,
                    onClick = onOpenBackupCenter,
                )
                SettingsRow(
                    title = stringResource(R.string.settings_backup_preferences),
                    summary = backupSummary,
                    enabled = true,
                    onClick = { showBackupDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                SettingsRow(
                    title = stringResource(R.string.settings_appearance_theme),
                    summary = stringResource(themeMode.labelRes),
                    enabled = true,
                    onClick = { showThemeDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                SettingsRow(
                    title = stringResource(R.string.settings_storage_telegram_cache),
                    summary = if (storage.loaded) {
                        Formatter.formatShortFileSize(context, storage.tdlibBytes)
                    } else {
                        stringResource(R.string.settings_storage_measuring)
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_storage_backup_cache),
                    summary = if (storage.loaded) {
                        Formatter.formatShortFileSize(context, storage.stagingBytes)
                    } else {
                        stringResource(R.string.settings_storage_measuring)
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.settings_storage_clear),
                    summary = stringResource(R.string.settings_storage_clear_summary),
                    enabled = true,
                    onClick = viewModel::clearBackupCache,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsRow(
                    title = stringResource(R.string.settings_about_version),
                    summary = BuildConfig.VERSION_NAME,
                )
            }

            // Bottom breathing room so the last row clears the navigation bar.
            Column(modifier = Modifier.padding(bottom = spacing.xl)) {}
        }
    }

    if (showThemeDialog) {
        ThemeModeDialog(
            current = themeMode,
            onSelect = { mode ->
                onThemeModeChange(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showBackupDialog) {
        BackupPreferenceDialog(
            current = preference,
            onSelect = { choice ->
                showBackupDialog = false
                if (choice == BackupPreference.SELECT_FOLDER) {
                    // The choice only becomes real once folders are confirmed; until then the
                    // previously persisted selection is untouched.
                    viewModel.loadFolders()
                    showFolderPicker = true
                } else {
                    viewModel.setBackupChoice(choice)
                    // Turning backup on is when upload progress starts to matter; asking no-ops
                    // below API 33 and when notifications are already enabled.
                    if (choice != BackupPreference.NOT_NOW) notifications.request()
                }
            },
            onDismiss = { showBackupDialog = false },
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            folders = folders,
            initiallySelected = bucketIds,
            onConfirm = { ids ->
                showFolderPicker = false
                viewModel.setBackupChoice(BackupPreference.SELECT_FOLDER, ids)
                if (ids.isNotEmpty()) notifications.request()
            },
            onDismiss = { showFolderPicker = false },
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.settings_sign_out_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text(stringResource(R.string.organization_cancel))
                }
            },
        )
    }
}

@Composable
private fun BackupPreferenceDialog(
    current: BackupPreference?,
    onSelect: (BackupPreference) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_preferences_dialog_title)) },
        text = {
            Column {
                BackupPreference.entries.forEach { preference ->
                    val selected = preference == current
                    Surface(onClick = { onSelect(preference) }, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = TelepicTokens.spacing.xs, horizontal = TelepicTokens.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = { onSelect(preference) })
                            Text(
                                text = stringResource(preference.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = TelepicTokens.spacing.md),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ThemeModeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_dialog_title)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    ThemeOptionRow(
                        mode = mode,
                        selected = mode == current,
                        onClick = { onSelect(mode) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = TelepicTokens.spacing
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs, horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Text(
                text = stringResource(mode.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = spacing.md),
            )
        }
    }
}
