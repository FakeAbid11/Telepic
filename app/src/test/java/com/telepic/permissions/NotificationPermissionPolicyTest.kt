package com.telepic.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification decision table, tested as pure logic (no framework calls).
 *
 * The asymmetry that matters: below Android 13 there is nothing to ask for and notifications simply
 * work, while on 13+ a disabled toggle genuinely hides the backup progress line — the app must treat
 * those two "no dialog" cases differently.
 */
class NotificationPermissionPolicyTest {

    private val api33 = 33
    private val api32 = 32
    private val api34 = 34

    @Test
    fun `sdk below 33 needs no permission`() {
        assertFalse(NotificationPermissionPolicy.isRequired(api32))
        assertEquals(NotificationPermissionState.NotRequired, NotificationPermissionPolicy.resolve(api32, false))
        // Even a globally enabled app is NotRequired: asking there is meaningless.
        assertEquals(NotificationPermissionState.NotRequired, NotificationPermissionPolicy.resolve(api32, true))
    }

    @Test
    fun `enabled notifications win regardless of sdk`() {
        assertTrue(NotificationPermissionPolicy.isRequired(api33))
        assertEquals(NotificationPermissionState.Granted, NotificationPermissionPolicy.resolve(api33, true))
        assertEquals(NotificationPermissionState.Granted, NotificationPermissionPolicy.resolve(api34, true))
    }

    @Test
    fun `sdk 33 or newer with notifications off is denied`() {
        assertEquals(NotificationPermissionState.Denied, NotificationPermissionPolicy.resolve(api33, false))
        assertEquals(NotificationPermissionState.Denied, NotificationPermissionPolicy.resolve(api34, false))
    }

    @Test
    fun `only the post-notification permission is ever requested`() {
        assertEquals("android.permission.POST_NOTIFICATIONS", NotificationPermissionPolicy.permissionToRequest())
    }

    @Test
    fun `canNotify is true except when denied`() {
        assertTrue(NotificationPermissionState.NotRequired.canNotify)
        assertTrue(NotificationPermissionState.Granted.canNotify)
        assertFalse(NotificationPermissionState.Denied.canNotify)
    }
}
