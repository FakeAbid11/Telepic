package com.telepic.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.BuildConfig
import com.telepic.R
import com.telepic.domain.cloud.CloudStatus
import com.telepic.onboarding.BackupPreference
import com.telepic.permissions.rememberNotificationPermissionState
import com.telepic.settings.BackupNetworkPreference
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

/** Human-readable label resource for the transport the backup engine may upload over. */
val BackupNetworkPreference.labelRes: Int
    get() = when (this) {
        BackupNetworkPreference.ANY_NETWORK -> R.string.settings_backup_network_any
        BackupNetworkPreference.WIFI_ONLY -> R.string.settings_backup_network_wifi
    }

/**
 * Short, honest wording for the Account section's connection row. It reports exactly what the Cloud
 * layer currently knows — "empty cloud" reads as connected, and a failure never reads as connected.
 */
private val CloudStatus.connectionSummaryRes: Int
    get() = when (this) {
        CloudStatus.Ready, CloudStatus.Refreshing, CloudStatus.Empty -> R.string.settings_connection_ready
        CloudStatus.Connecting, CloudStatus.Initializing -> R.string.settings_connection_connecting
        CloudStatus.Offline -> R.string.settings_connection_offline
        CloudStatus.NotAuthenticated -> R.string.settings_account_status_disconnected
        // An outstanding confirmation offer is still "not set up": Telepic has nowhere to back up
        // until the user answers, and it must not read as connected in the meantime.
        CloudStatus.DestinationMissing, is CloudStatus.DestinationConfirmation ->
            R.string.settings_connection_no_channel
        is CloudStatus.DestinationInvalid, is CloudStatus.Failed -> R.string.settings_connection_problem
    }

/**
 * Settings: the real Telegram account status (with sign-out), the backup preference editor (the same
 * persisted choice onboarding writes, applied immediately through the coordinator), the transport and
 * media preferences, the app lock, measured cache sizes and library counts, the theme picker and
 * version/licence information. Rows reflect behavior that exists — nothing here reports a state the
 * app cannot honor, which is why the app-lock switch is disabled on a device with no credential.
 *
 * Settings is not a bottom-bar tab, so it carries a leading back affordance like every other non-tab
 * screen (album contents, organization, Backup Center) rather than relying on system Back alone.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBackupCenter: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val spacing = TelepicTokens.spacing
    val context = LocalContext.current
    val notifications = rememberNotificationPermissionState()
    // Dialog visibility survives rotation: dismissing every dialog on recreation read as a bug.
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showBackupDialog by rememberSaveable { mutableStateOf(false) }
    var showNetworkDialog by rememberSaveable { mutableStateOf(false) }
    var showSignOutConfirm by rememberSaveable { mutableStateOf(false) }
    // The picker's list is loaded asynchronously, so a restored picker would open on an empty folder
    // set that the user did not ask for. The other dialogs here stay saved: their subject is persisted
    // state (the theme, the backup choice, the session), not a list that has to arrive first.
    var showFolderPicker by remember { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val preference by viewModel.backupPreference.collectAsStateWithLifecycle()
    val bucketIds by viewModel.backupBucketIds.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val foldersLoaded by viewModel.foldersLoaded.collectAsStateWithLifecycle()
    val storage by viewModel.storageSummary.collectAsStateWithLifecycle()
    val cloudStatus by viewModel.cloudStatus.collectAsStateWithLifecycle()
    val channelTitle by viewModel.backupChannelTitle.collectAsStateWithLifecycle()
    val networkPreference by viewModel.backupNetworkPreference.collectAsStateWithLifecycle()
    val requiresCharging by viewModel.backupRequiresCharging.collectAsStateWithLifecycle()
    val requiresBatteryNotLow by viewModel.backupRequiresBatteryNotLow.collectAsStateWithLifecycle()
    val showVideos by viewModel.showVideosInTimeline.collectAsStateWithLifecycle()
    val showGifs by viewModel.showGifsInTimeline.collectAsStateWithLifecycle()
    val startMuted by viewModel.startVideosMuted.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val isDeviceSecure by viewModel.isDeviceSecure.collectAsStateWithLifecycle()
    val localCount by viewModel.localLibraryCount.collectAsStateWithLifecycle()
    val localCountLoaded by viewModel.localLibraryCountLoaded.collectAsStateWithLifecycle()
    val cloudCount by viewModel.cloudLibraryCount.collectAsStateWithLifecycle()

    // The device's credential state is re-read on resume. The user sets a PIN in system settings and
    // comes back; the app-lock row must reflect that on the frame they arrive, not after a restart.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDeviceSecurity()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // "Back up now" only means something once an automatic option is configured. SELECT_FOLDER with
    // no folders picked backs up nothing, so it counts as unconfigured — matching the coordinator,
    // which treats an empty selection as the honest equivalent of NOT_NOW.
    val backupConfigured = preference == BackupPreference.BACKUP_ALL ||
        (preference == BackupPreference.SELECT_FOLDER && bucketIds.isNotEmpty())

    val accountSummary = when (val state = authState) {
        is TelegramAuthState.Authorized -> state.user.displayName
        else -> stringResource(R.string.settings_account_status_disconnected)
    }
    val backupSummary = when (preference) {
        BackupPreference.BACKUP_ALL -> stringResource(BackupPreference.BACKUP_ALL.labelRes)
        BackupPreference.SELECT_FOLDER ->
            pluralStringResource(R.plurals.settings_backup_pref_folders, bucketIds.size, bucketIds.size)
        BackupPreference.NOT_NOW -> stringResource(BackupPreference.NOT_NOW.labelRes)
        null -> stringResource(R.string.settings_backup_pref_unset)
    }

    // A null count is "not measured", never zero — the row must not claim the library is empty.
    val measuredLocalCount = localCount
    val localLibrarySummary = when {
        !localCountLoaded -> stringResource(R.string.settings_library_local_counting)
        measuredLocalCount != null ->
            pluralStringResource(R.plurals.settings_library_local_summary, measuredLocalCount, measuredLocalCount)
        else -> stringResource(R.string.settings_library_local_unknown)
    }
    val measuredCloudCount = cloudCount
    val cloudLibrarySummary = if (measuredCloudCount != null) {
        pluralStringResource(R.plurals.settings_library_cloud_summary, measuredCloudCount, measuredCloudCount)
    } else {
        stringResource(R.string.settings_library_cloud_unknown)
    }

    // The scheme's own background rather than `mediaBackdrop` — deliberately. Nothing on this screen is
    // media, so it takes the document-style surface; the six screens that show thumbnails use
    // `mediaBackdrop` (see `TelepicExtendedColors.mediaBackdrop` and Backup Center for the rule).
    //
    // The centring Box is what caps the measure: on a tablet an uncapped settings row leaves the label
    // and its summary most of a screen apart, so one sentence takes a head-turn to read. The Box fills
    // the screen and the Column asks for `contentMaxWidth`, so the surface and the scroll gesture stay
    // edge-to-edge while the text does not. Material3's Surface has no content-alignment parameter in
    // this version, which is why this is an explicit Box rather than a `Surface` argument.
    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = spacing.contentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                ScreenHeader(
                    title = stringResource(R.string.settings_title),
                    onBack = onBack,
                )

                SettingsSection(title = stringResource(R.string.settings_section_account)) {
                    // Info row: enabled (not dimmed) with no onClick — a real state read, not a stub.
                    SettingsRow(
                        title = stringResource(R.string.settings_account_telegram),
                        summary = accountSummary,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_account_connection),
                        summary = stringResource(cloudStatus.connectionSummaryRes),
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_account_backup_channel),
                        summary = channelTitle ?: stringResource(R.string.settings_backup_channel_none),
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
                    SettingsRow(
                        title = stringResource(R.string.settings_backup_now),
                        summary = if (backupConfigured) {
                            stringResource(R.string.settings_backup_now_summary)
                        } else {
                            stringResource(R.string.settings_backup_now_unavailable)
                        },
                        enabled = backupConfigured,
                        onClick = viewModel::backupNow,
                    )
                    // The three rows below are WorkManager constraints: a run is simply not started until
                    // the condition holds, so nothing is ever failed for a condition the user chose.
                    SettingsRow(
                        title = stringResource(R.string.settings_backup_network),
                        summary = stringResource(networkPreference.labelRes),
                        enabled = true,
                        onClick = { showNetworkDialog = true },
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_backup_charge_only),
                        summary = stringResource(R.string.settings_backup_charge_only_summary),
                        checked = requiresCharging,
                        onCheckedChange = viewModel::setBackupRequiresCharging,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_backup_battery_not_low),
                        summary = stringResource(R.string.settings_backup_battery_not_low_summary),
                        checked = requiresBatteryNotLow,
                        onCheckedChange = viewModel::setBackupRequiresBatteryNotLow,
                    )
                    // Informational: Telepic has no re-encoding path, so "Original" is the only truthful
                    // value and the row states it rather than offering a choice it cannot honour.
                    SettingsRow(
                        title = stringResource(R.string.settings_backup_quality),
                        summary = stringResource(R.string.settings_backup_quality_value),
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_library)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_library_local),
                        summary = localLibrarySummary,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_library_cloud),
                        summary = cloudLibrarySummary,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_media)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_media_show_videos),
                        summary = stringResource(R.string.settings_media_show_videos_summary),
                        checked = showVideos,
                        onCheckedChange = viewModel::setShowVideosInTimeline,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_media_show_gifs),
                        summary = stringResource(R.string.settings_media_show_gifs_summary),
                        checked = showGifs,
                        onCheckedChange = viewModel::setShowGifsInTimeline,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_media_start_muted),
                        summary = stringResource(R.string.settings_media_start_muted_summary),
                        checked = startMuted,
                        onCheckedChange = viewModel::setStartVideosMuted,
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

                SettingsSection(title = stringResource(R.string.settings_section_security)) {
                    // The lock is enforced by the device's own credential, so with none configured there
                    // is nothing to authenticate against: the row says so and is switched off rather than
                    // offering a toggle that would silently never fire.
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_security_app_lock),
                        // The summary tracks the switch, because the lock does not fire when you enable it —
                        // it fires on the next foregrounding. A static description left the row looking like
                        // a toggle that did nothing; this one says what just changed.
                        summary = when {
                            !isDeviceSecure -> stringResource(R.string.settings_security_app_lock_unavailable)
                            appLockEnabled -> stringResource(R.string.settings_security_app_lock_on)
                            else -> stringResource(R.string.settings_security_app_lock_summary)
                        },
                        checked = appLockEnabled,
                        enabled = isDeviceSecure,
                        onCheckedChange = viewModel::setAppLockEnabled,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                    // Trash lives here because it is a storage concern, and because the Viewer's own
                    // confirmation can only offer a single-step Undo — anything older has to be
                    // reachable. Its previous sole entry point was a bare text button inside Albums,
                    // which users did not find.
                    SettingsRow(
                        title = stringResource(R.string.settings_storage_trash),
                        summary = stringResource(R.string.settings_storage_trash_summary),
                        enabled = true,
                        onClick = onOpenTrash,
                    )
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
                    SettingsRow(
                        title = stringResource(R.string.settings_storage_image_cache),
                        summary = stringResource(R.string.settings_storage_image_cache_summary),
                        enabled = true,
                        onClick = viewModel::clearImageCache,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_version),
                        summary = BuildConfig.VERSION_NAME,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_about_licenses),
                        summary = stringResource(R.string.settings_about_licenses_summary),
                        enabled = true,
                        onClick = { showLicenses = true },
                    )
                }

                // Bottom breathing room so the last row clears the navigation bar.
                Column(modifier = Modifier.padding(bottom = spacing.xl)) {}
            }
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

    if (showNetworkDialog) {
        BackupNetworkDialog(
            current = networkPreference,
            onSelect = { choice ->
                showNetworkDialog = false
                viewModel.setBackupNetworkPreference(choice)
            },
            onDismiss = { showNetworkDialog = false },
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            folders = folders,
            initiallySelected = bucketIds,
            loading = !foldersLoaded,
            onConfirm = { ids ->
                showFolderPicker = false
                viewModel.setBackupChoice(BackupPreference.SELECT_FOLDER, ids)
                if (ids.isNotEmpty()) notifications.request()
            },
            onDismiss = { showFolderPicker = false },
        )
    }

    if (showLicenses) {
        OpenSourceLicensesDialog(onDismiss = { showLicenses = false })
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

/** Picks which connection the backup engine may upload over. Applied as a WorkManager constraint. */
@Composable
private fun BackupNetworkDialog(
    current: BackupNetworkPreference,
    onSelect: (BackupNetworkPreference) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_network_dialog_title)) },
        text = {
            Column {
                BackupNetworkPreference.entries.forEach { preference ->
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
