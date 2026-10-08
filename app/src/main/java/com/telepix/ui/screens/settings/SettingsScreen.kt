package com.telepix.ui.screens.settings

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
import androidx.compose.ui.res.stringResource
import com.telepix.BuildConfig
import com.telepix.R
import com.telepix.settings.ThemeMode
import com.telepix.ui.components.ScreenHeader
import com.telepix.ui.theme.TelepixTokens

/** Human-readable label resource for a [ThemeMode]. */
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

/**
 * Phase 1 Settings foundation.
 *
 * Presents the Account, Backup, Appearance, Storage and About sections defined by the PRD.
 * Only Appearance → Theme is functional in this phase; the rest are clearly marked as
 * arriving later rather than faking behavior.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepixTokens.spacing
    var showThemeDialog by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenHeader(title = stringResource(R.string.settings_title))

            SettingsSection(title = stringResource(R.string.settings_section_account)) {
                SettingsRow(
                    title = stringResource(R.string.settings_account_telegram),
                    summary = stringResource(R.string.settings_account_telegram_summary),
                    enabled = false,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_backup)) {
                SettingsRow(
                    title = stringResource(R.string.settings_backup_preferences),
                    summary = stringResource(R.string.settings_backup_preferences_summary),
                    enabled = false,
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
                    title = stringResource(R.string.settings_storage_cache),
                    summary = stringResource(R.string.settings_storage_cache_summary),
                    enabled = false,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsRow(
                    title = stringResource(R.string.settings_about_version),
                    summary = BuildConfig.VERSION_NAME,
                    enabled = false,
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
    val spacing = TelepixTokens.spacing
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
