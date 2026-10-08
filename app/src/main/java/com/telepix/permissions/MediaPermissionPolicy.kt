package com.telepix.permissions

import android.Manifest
import android.os.Build

/**
 * Pure, SDK-aware media-permission logic.
 *
 * Separated from Android framework calls so the decision table can be unit-tested on the JVM.
 * The controller passes in real grant/rationale lookups; this object only decides.
 *
 * Deliberately requests only photo/video access — no location, no unrelated permissions.
 */
object MediaPermissionPolicy {

    // Compile-time String constants (inlined), safe to reference from unit tests.
    private const val PERM_READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE
    private const val PERM_READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES
    private const val PERM_READ_MEDIA_VIDEO = Manifest.permission.READ_MEDIA_VIDEO
    private const val PERM_READ_MEDIA_VISUAL_USER_SELECTED =
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

    /** The permissions Telepix should request for the given platform version. */
    fun permissionsToRequest(sdkInt: Int): List<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            listOf(PERM_READ_MEDIA_IMAGES, PERM_READ_MEDIA_VIDEO, PERM_READ_MEDIA_VISUAL_USER_SELECTED)
        sdkInt == Build.VERSION_CODES.TIRAMISU ->
            listOf(PERM_READ_MEDIA_IMAGES, PERM_READ_MEDIA_VIDEO)
        else ->
            listOf(PERM_READ_EXTERNAL_STORAGE)
    }

    /**
     * Resolves the effective [MediaPermissionState].
     *
     * @param sdkInt current [Build.VERSION.SDK_INT].
     * @param isGranted real per-permission grant lookup (e.g. ContextCompat.checkSelfPermission).
     * @param shouldShowRationale real per-permission rationale lookup from the Activity.
     * @param hasRequestedBefore whether Telepix has already asked in this install/flow, used to
     *   separate [MediaPermissionState.NotRequested] from a genuine denial.
     */
    fun resolve(
        sdkInt: Int,
        isGranted: (String) -> Boolean,
        shouldShowRationale: (String) -> Boolean,
        hasRequestedBefore: Boolean,
    ): MediaPermissionState {
        val fullAccess = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU ->
                isGranted(PERM_READ_MEDIA_IMAGES) && isGranted(PERM_READ_MEDIA_VIDEO)
            else -> isGranted(PERM_READ_EXTERNAL_STORAGE)
        }
        if (fullAccess) return MediaPermissionState.Granted

        val partialAccess = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(PERM_READ_MEDIA_VISUAL_USER_SELECTED)
        if (partialAccess) return MediaPermissionState.Partial

        if (!hasRequestedBefore) return MediaPermissionState.NotRequested

        val canAskAgain = permissionsToRequest(sdkInt).any { shouldShowRationale(it) }
        return if (canAskAgain) MediaPermissionState.Denied else MediaPermissionState.PermanentlyDenied
    }
}
