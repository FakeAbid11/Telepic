package com.telepic.permissions

import android.Manifest
import android.os.Build

/**
 * Pure, SDK-aware media-permission logic.
 *
 * Separated from Android framework calls so the decision table can be unit-tested on the JVM.
 * The controller passes in real grant/rationale lookups; this object only decides.
 *
 * Requests the version-appropriate photo/video access, plus (API 29+) the media-location permission
 * needed to read GPS that is already embedded in the user's own files. It never requests *device*
 * location, and location is deliberately best-effort: [resolve] keys the library's usability on
 * media access alone, so a denied media-location grant degrades the map, not the app.
 */
object MediaPermissionPolicy {

    // Compile-time String constants (inlined), safe to reference from unit tests.
    private const val PERM_READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE
    private const val PERM_WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
    private const val PERM_READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES
    private const val PERM_READ_MEDIA_VIDEO = Manifest.permission.READ_MEDIA_VIDEO
    private const val PERM_READ_MEDIA_VISUAL_USER_SELECTED =
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private const val PERM_ACCESS_MEDIA_LOCATION = Manifest.permission.ACCESS_MEDIA_LOCATION

    /** The permissions Telepic should request for the given platform version. */
    fun permissionsToRequest(sdkInt: Int): List<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            listOf(
                PERM_READ_MEDIA_IMAGES,
                PERM_READ_MEDIA_VIDEO,
                PERM_READ_MEDIA_VISUAL_USER_SELECTED,
                PERM_ACCESS_MEDIA_LOCATION,
            )
        sdkInt == Build.VERSION_CODES.TIRAMISU ->
            listOf(PERM_READ_MEDIA_IMAGES, PERM_READ_MEDIA_VIDEO, PERM_ACCESS_MEDIA_LOCATION)
        sdkInt <= Build.VERSION_CODES.P ->
            // API 26–28: the legacy MediaStore is used for BOTH reading the library and publishing a
            // restored original ("Save to device"), so write access must be requested alongside read.
            // Media location is not requested here: the platform only redacts EXIF from API 29, so on
            // API 26–28 reading a file already returns its embedded GPS unchanged.
            listOf(PERM_READ_EXTERNAL_STORAGE, PERM_WRITE_EXTERNAL_STORAGE)
        else ->
            // API 29–32: scoped storage exposes reads through READ_EXTERNAL_STORAGE; writes into the
            // app's own MediaStore entries need no permission. Media location is requested because the
            // same scoped-storage redaction applies from API 29.
            listOf(PERM_READ_EXTERNAL_STORAGE, PERM_ACCESS_MEDIA_LOCATION)
    }

    /**
     * Resolves the effective [MediaPermissionState].
     *
     * @param sdkInt current [Build.VERSION.SDK_INT].
     * @param isGranted real per-permission grant lookup (e.g. ContextCompat.checkSelfPermission).
     * @param shouldShowRationale real per-permission rationale lookup from the Activity.
     * @param hasRequestedBefore whether Telepic has already asked in this install/flow, used to
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
