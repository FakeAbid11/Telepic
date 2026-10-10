package com.telepic.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure permission decision table across Android versions without touching the
 * real system dialogs (which are not meaningfully unit-testable).
 */
class MediaPermissionPolicyTest {

    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val video = Manifest.permission.READ_MEDIA_VIDEO
    private val userSelected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val legacyStorage = Manifest.permission.READ_EXTERNAL_STORAGE

    private fun resolve(
        sdk: Int,
        granted: Set<String>,
        rationale: Set<String> = emptySet(),
        hasRequested: Boolean = true,
    ): MediaPermissionState = MediaPermissionPolicy.resolve(
        sdkInt = sdk,
        isGranted = { permission -> permission in granted },
        shouldShowRationale = { permission -> permission in rationale },
        hasRequestedBefore = hasRequested,
    )

    @Test
    fun `requests granular media permissions on Android 13+`() {
        val request33 = MediaPermissionPolicy.permissionsToRequest(33).toSet()
        assertTrue(images in request33)
        assertTrue(video in request33)
        assertTrue(legacyStorage !in request33)
    }

    @Test
    fun `requests partial selection permission on Android 14+`() {
        val request34 = MediaPermissionPolicy.permissionsToRequest(34).toSet()
        assertTrue(userSelected in request34)
    }

    @Test
    fun `requests legacy storage on or below Android 12`() {
        val request32 = MediaPermissionPolicy.permissionsToRequest(32)
        assertEquals(setOf(legacyStorage), request32.toSet())
    }

    @Test
    fun `full media grant resolves to granted on Android 13`() {
        assertEquals(
            MediaPermissionState.Granted,
            resolve(33, granted = setOf(images, video)),
        )
    }

    @Test
    fun `only user-selected on Android 14 resolves to partial`() {
        assertEquals(
            MediaPermissionState.Partial,
            resolve(34, granted = setOf(userSelected)),
        )
    }

    @Test
    fun `nothing granted before ever asking resolves to not requested`() {
        assertEquals(
            MediaPermissionState.NotRequested,
            resolve(33, granted = emptySet(), hasRequested = false),
        )
    }

    @Test
    fun `denied with rationale available resolves to denied`() {
        assertEquals(
            MediaPermissionState.Denied,
            resolve(33, granted = emptySet(), rationale = setOf(images), hasRequested = true),
        )
    }

    @Test
    fun `denied without rationale resolves to permanently denied`() {
        assertEquals(
            MediaPermissionState.PermanentlyDenied,
            resolve(33, granted = emptySet(), rationale = emptySet(), hasRequested = true),
        )
    }

    @Test
    fun `legacy storage grant on Android 12 resolves to granted`() {
        assertEquals(
            MediaPermissionState.Granted,
            resolve(32, granted = setOf(legacyStorage)),
        )
    }

    @Test
    fun `partial access counts as having access`() {
        assertTrue(MediaPermissionState.Partial.hasAccess)
        assertTrue(MediaPermissionState.Granted.hasAccess)
        assertTrue(!MediaPermissionState.Denied.hasAccess)
        assertTrue(!MediaPermissionState.NotRequested.hasAccess)
    }
}
