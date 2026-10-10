package com.telepic.permissions

import android.Manifest
import android.os.Build

/**
 * Pure, SDK-aware notification-permission logic.
 *
 * Mirrors [MediaPermissionPolicy]: the controller supplies the real platform lookup and this object
 * only decides, so the table is unit-testable on the JVM.
 *
 * Telepic asks for exactly one permission here, and only for the backup progress notification — the
 * app never posts marketing or reminder notifications, so nothing else needs a channel.
 */
object NotificationPermissionPolicy {

    // Compile-time String constant (inlined), safe to reference from unit tests.
    private const val PERM_POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    /** The permission is only meaningful from Android 13 (API 33) upward. */
    fun isRequired(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** The single permission Telepic requests for notifications. */
    fun permissionToRequest(): String = PERM_POST_NOTIFICATIONS

    /**
     * Resolves the effective [NotificationPermissionState].
     *
     * @param sdkInt current [Build.VERSION.SDK_INT].
     * @param areNotificationsEnabled the real platform lookup
     *   (`NotificationManagerCompat.areNotificationsEnabled()`), which also reflects a user who
     *   turned notifications off for the app wholesale.
     */
    fun resolve(sdkInt: Int, areNotificationsEnabled: Boolean): NotificationPermissionState = when {
        !isRequired(sdkInt) -> NotificationPermissionState.NotRequired
        areNotificationsEnabled -> NotificationPermissionState.Granted
        else -> NotificationPermissionState.Denied
    }
}
