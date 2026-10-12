package com.telepic.ui.onboarding.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.NotificationPermissionState
import com.telepic.ui.theme.TelepicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose tests for the Permissions step, which now asks for two different things: the
 * media access the app cannot work without, and the notification access that only drives the backup
 * progress line.
 *
 * The property worth pinning is that they stay **independent** — the optional ask must be visible
 * and actionable on its own, and must disappear entirely where the permission does not exist (below
 * API 33) or has already been granted. Bundling them into one prompt is exactly what this design
 * avoids, so a regression that re-couples them is what these tests would catch.
 *
 * The viewport is widened deliberately: this is the only onboarding step with two sections, and at
 * Robolectric's default 320x470dp the notification card falls below the fold of the step's own
 * verticalScroll, so a click would never reach it. Asserting on a node that merely exists, off
 * screen, would not prove the button works.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class PermissionsStepTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        permissionState: MediaPermissionState = MediaPermissionState.Granted,
        notificationState: NotificationPermissionState = NotificationPermissionState.Denied,
        onRequestNotifications: () -> Unit = {},
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                PermissionsStep(
                    permissionState = permissionState,
                    notificationState = notificationState,
                    onRequestNotifications = onRequestNotifications,
                )
            }
        }
    }

    @Test
    fun `denied notifications offer the ask, and tapping it reports the request`() {
        var requests = 0
        render(
            notificationState = NotificationPermissionState.Denied,
            onRequestNotifications = { requests++ },
        )

        composeRule.onNodeWithText("Notifications off").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_PERMISSIONS_ALLOW_NOTIFICATIONS).performClick()
        composeRule.waitForIdle()

        assertEquals(1, requests)
    }

    @Test
    fun `granted notifications report the state and offer nothing to tap`() {
        render(notificationState = NotificationPermissionState.Granted)

        composeRule.onNodeWithText("Notifications on").assertIsDisplayed()
        // Nothing is left to ask for, so the action is gone rather than sitting there as a no-op the
        // user could keep tapping with no visible effect.
        composeRule.onNodeWithTag(TAG_PERMISSIONS_ALLOW_NOTIFICATIONS).assertDoesNotExist()
    }

    @Test
    fun `below Android 13 the notification section is absent`() {
        render(notificationState = NotificationPermissionState.NotRequired)

        // Before API 33 the permission does not exist: there is nothing to explain, and a card
        // claiming "notifications on" would be reporting on a permission the device never asked for.
        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_PERMISSIONS_ALLOW_NOTIFICATIONS).assertDoesNotExist()
    }

    @Test
    fun `the media and notification asks are independent`() {
        render(
            permissionState = MediaPermissionState.PermanentlyDenied,
            notificationState = NotificationPermissionState.Denied,
        )

        // A blocked media permission must not take the optional notification ask down with it — the
        // notification card's presence is decided by its own state alone.
        composeRule.onNodeWithText("Access blocked").assertIsDisplayed()
        composeRule.onNodeWithText("Notifications off").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_PERMISSIONS_ALLOW_NOTIFICATIONS).assertIsDisplayed()
    }
}
