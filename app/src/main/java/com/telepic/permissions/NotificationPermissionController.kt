package com.telepic.permissions

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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

/** UI-facing snapshot of the notification permission plus the actions that can still change it. */
@Immutable
data class NotificationPermissionUiState(
    val state: NotificationPermissionState,
    /**
     * Ask the system for [android.Manifest.permission.POST_NOTIFICATIONS]. A no-op unless asking can
     * still help, so callers can fire it straight from a "backup enabled" click without first
     * checking the SDK level or the current grant.
     */
    val request: () -> Unit,
    /**
     * Open Telepic's notification settings. The only path that still works once the platform will no
     * longer show the request dialog — without it that state was a button that did nothing.
     */
    val openSettings: () -> Unit,
)

/**
 * Observes and requests the notification permission used by the backup progress notification.
 *
 * The real toggle ([NotificationManagerCompat.areNotificationsEnabled]) is re-read on every resume,
 * so granting it in system Settings is reflected without a restart; the OS stays authoritative for
 * *whether* notifications are on. The one thing the OS does not expose is how many times the app has
 * asked, and that is what separates "denied, ask again" from "denied for good" — so just that counter
 * is persisted, in a private preferences file.
 */
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionUiState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sdkInt = Build.VERSION.SDK_INT

    // Not saveable on purpose: this only forces a re-read of the platform toggle, and resume
    // refreshes it anyway, so surviving process death would be state for no benefit.
    var refreshTick by remember { mutableIntStateOf(0) }

    val prefs = remember(context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var attempts by remember { mutableIntStateOf(prefs.getInt(KEY_ATTEMPTS, 0)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Count **denials**, not launches.
        //
        // The counter used to be incremented before the request was launched, so it recorded how
        // many times the dialog was *shown*. The platform's own rule is "two denials", and a user who
        // granted once, had the grant revoked in system Settings, and then denied the re-request
        // had consumed two attempts for one denial — which put the app into "open settings" while the
        // platform would still have shown the dialog.
        if (!granted) {
            val next = attempts + 1
            prefs.edit().putInt(KEY_ATTEMPTS, next).apply()
            attempts = next
        }
        refreshTick++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = remember(sdkInt, refreshTick, context, attempts) {
        NotificationPermissionPolicy.resolve(
            sdkInt = sdkInt,
            areNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            priorAttempts = attempts,
        )
    }

    return remember(state, launcher, prefs) {
        NotificationPermissionUiState(
            state = state,
            request = {
                // Denied is the only state where the dialog can still appear; below API 33 the
                // permission does not exist, and asking on a granted app is noise. The attempt is
                // counted by the launcher's *denial* callback, so it records denials.
                if (state == NotificationPermissionState.Denied) {
                    launcher.launch(NotificationPermissionPolicy.permissionToRequest())
                }
            },
            openSettings = {
                // Never crash on a missing settings activity: a ROM without it simply does nothing
                // rather than taking the app down from a tap.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

/** Private file holding the one thing the platform does not expose: how often we have asked. */
private const val PREFS = "telepic_permissions"
private const val KEY_ATTEMPTS = "notification_permission_attempts"
