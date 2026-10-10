package com.telepic.permissions

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** UI-facing snapshot of the notification permission plus the action that asks for it. */
@Immutable
data class NotificationPermissionUiState(
    val state: NotificationPermissionState,
    /**
     * Ask the system for [android.Manifest.permission.POST_NOTIFICATIONS]. A no-op unless asking can
     * still help, so callers can fire it straight from a "backup enabled" click without first
     * checking the SDK level or the current grant.
     */
    val request: () -> Unit,
)

/**
 * Observes and requests the notification permission used by the backup progress notification.
 *
 * The real toggle ([NotificationManagerCompat.areNotificationsEnabled]) is re-read on every resume,
 * so granting it in system Settings is reflected without a restart and nothing is persisted — the OS
 * stays authoritative, exactly like [rememberMediaPermissionState].
 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionUiState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sdkInt = Build.VERSION.SDK_INT

    // Not saveable on purpose: this only forces a re-read of the platform toggle, and resume
    // refreshes it anyway, so surviving process death would be state for no benefit.
    var refreshTick by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshTick++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = remember(sdkInt, refreshTick, context) {
        NotificationPermissionPolicy.resolve(
            sdkInt = sdkInt,
            areNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }

    return remember(state, launcher) {
        NotificationPermissionUiState(
            state = state,
            request = {
                // Denied is the only state where the dialog can still appear; below API 33 the
                // permission does not exist, and asking on a granted app is noise.
                if (state == NotificationPermissionState.Denied) {
                    launcher.launch(NotificationPermissionPolicy.permissionToRequest())
                }
            },
        )
    }
}
