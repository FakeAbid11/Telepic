package com.telepic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens

/** One third-party component Telepic links, with the licence it is distributed under. */
data class OpenSourceLibrary(
    val name: String,
    val version: String,
    val licence: String,
)

/**
 * The open-source components Telepic actually ships, mirroring `gradle/libs.versions.toml`.
 *
 * Hand-maintained rather than generated: pulling in a licence-reporting plugin for a list this short
 * would add a build dependency and an annotation processor to save one line per release. The trade-off
 * is that it must be updated whenever a runtime dependency changes — which is why the versions here
 * are the exact ones from the version catalog rather than ranges.
 *
 * The versions below are the ones in `gradle/libs.versions.toml` as of this change. They had drifted
 * (Kotlin, Coroutines, Core KTX, DataStore, Room, WorkManager and ExifInterface were all several
 * releases behind), and `androidx.biometric` — which ships in the APK for the app-lock prompt — was
 * missing from the list entirely, which is a missing third-party notice rather than a cosmetic gap.
 *
 * Test-only libraries (JUnit, Robolectric, the Compose UI test artifacts) are deliberately absent:
 * they are not distributed in the APK, so they are not part of what a user is running.
 */
val TelepicOpenSourceLibraries: List<OpenSourceLibrary> = listOf(
    OpenSourceLibrary("Kotlin Standard Library & Coroutines", "2.2.10 · 1.11.0", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Core KTX", "1.18.0", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Lifecycle", "2.8.6", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Activity Compose", "1.9.3", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Biometric (app lock)", "1.1.0", "Apache License 2.0"),
    OpenSourceLibrary("Jetpack Compose (UI, Foundation, Runtime)", "BOM 2024.10.01", "Apache License 2.0"),
    OpenSourceLibrary("Material 3 (Compose)", "BOM 2024.10.01", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Navigation Compose", "2.8.3", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX DataStore", "1.2.1", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Paging 3", "3.3.2", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Room", "2.8.5", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX WorkManager", "2.12.0", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX ExifInterface", "1.4.2", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Media3 / ExoPlayer", "1.4.1", "Apache License 2.0"),
    OpenSourceLibrary("Coil (image loading)", "2.7.0", "Apache License 2.0"),
    OpenSourceLibrary("osmdroid", "6.1.20", "Apache License 2.0"),
    OpenSourceLibrary("TDLib (Telegram Database Library)", "tdlib-android 0.1.1", "Boost Software License 1.0"),
)

/**
 * The licences sheet. Opened from About, and read-only: it lists what ships and under what terms,
 * which is the obligation an Apache-2.0/BSL-1.0 dependency set actually imposes.
 */
@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val spacing = TelepicTokens.spacing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_licenses)) },
        text = {
            Column(
                // Bounded so `verticalScroll` has a finite viewport to scroll within; without it an
                // over-tall child in the dialog's text slot simply overflows past the buttons.
                modifier = Modifier
                    .heightIn(max = TelepicTokens.spacing.dialogMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.settings_licenses_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TelepicOpenSourceLibraries.forEach { library ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                        Text(
                            text = library.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${library.version} · ${library.licence}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_licenses_map_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
