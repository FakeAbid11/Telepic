package com.telepic.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** UI-facing snapshot of media permission state plus the actions the screen can trigger. */
@Immutable
data class MediaPermissionUiState(
    val state: MediaPermissionState,
    val requestPermission: () -> Unit,
    val openAppSettings: () -> Unit,
)

/**
 * Observes and requests photo/video permission using the platform's real APIs.
 *
 * State is recomputed on every resume (so returning from app Settings is reflected) and after
 * each request result. Nothing is persisted — Android remains the source of truth.
 */
@Composable
fun rememberMediaPermissionState(): MediaPermissionUiState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val sdkInt = Build.VERSION.SDK_INT

    var hasRequested by rememberSaveable { mutableStateOf(false) }
    var refreshTick by rememberSaveable { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasRequested = true
        refreshTick++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = remember(sdkInt, hasRequested, refreshTick, context, activity) {
        MediaPermissionPolicy.resolve(
            sdkInt = sdkInt,
            isGranted = { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            },
            shouldShowRationale = { permission ->
                activity?.shouldShowRequestPermissionRationale(permission) ?: false
            },
            hasRequestedBefore = hasRequested,
        )
    }

    return remember(state, context, sdkInt) {
        MediaPermissionUiState(
            state = state,
            requestPermission = {
                hasRequested = true
                launcher.launch(MediaPermissionPolicy.permissionsToRequest(sdkInt).toTypedArray())
            },
            openAppSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
        )
    }
}

/**
 * A caller-forced snapshot for screens whose permission state is supplied by the test. It registers
 * no activity-result launcher and no lifecycle observer — only the real controller owns those — and
 * its actions are no-ops, so a forced state can never trigger a platform request.
 */
@Composable
fun rememberForcedMediaPermissionState(state: MediaPermissionState): MediaPermissionUiState =
    remember(state) {
        MediaPermissionUiState(
            state = state,
            requestPermission = {},
            openAppSettings = {},
        )
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
