package com.telepic.permissions

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-scoped observation of whether Telepic can read the user's photos and videos.
 *
 * The Compose controller ([rememberMediaPermissionState]) recomputes the state on every composition,
 * which is right for the screens but useless to anything that has to react to a grant *outside* a
 * composition — most importantly the one-time map backfill, and the case where a grant already exists
 * at process start. There is no broadcast for a runtime-permission change, so the honest app-wide
 * signal is the Activity lifecycle: every grant (from the system dialog, or from Settings) is followed
 * by a resume of an Activity. [hasAccess] is recomputed on each resume and only ever reports what the
 * platform currently grants, so it cannot drift from the OS.
 *
 * [hasAccess] is "full *or* partial" access: partial access still lets the app read the selected
 * items, which is enough to read their EXIF, so it is access for the purpose this exists.
 */
class MediaPermissionMonitor(application: Application) {

    private val appContext = application.applicationContext

    private val _hasAccess = MutableStateFlow(resolveHasAccess())

    /** Whether the app currently holds media access (full or user-selected partial). */
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                // `onActivityResumed`, not `onActivityStarted`: the runtime permission dialog only
                // pauses the requesting Activity (it is translucent), so a granted dialog resumes it
                // without a stop/start pair. A resume is also what fires after a trip to app Settings.
                override fun onActivityResumed(activity: Activity) {
                    _hasAccess.value = resolveHasAccess()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun resolveHasAccess(): Boolean = MediaPermissionPolicy.resolve(
        sdkInt = Build.VERSION.SDK_INT,
        isGranted = { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
        },
        // Rationale is irrelevant to whether access is held; a denied/no-rationale state still
        // resolves below the access branches. Passing the real lookup would need an Activity, which is
        // exactly what this class exists to avoid depending on.
        shouldShowRationale = { false },
        // If access is held, the request history cannot change the answer.
        hasRequestedBefore = true,
    ).hasAccess
}
