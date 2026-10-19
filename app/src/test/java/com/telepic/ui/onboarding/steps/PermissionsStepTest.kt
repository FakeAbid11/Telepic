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
 * The viewport is widened deliberately: this is the only onboarding step with two labelled sections
 * plus a privacy note, and at Robolectric's default 320x470dp the notification card falls below the
 * fold of the step's own verticalScroll, so a click would never reach it. Asserting on a node that
 * merely exists, off screen, would not prove the button works — so the viewport is tall enough for
 * the whole step.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h1200dp")
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

    @Test
    fun `each section states whether it is required or optional`() {
        render(
            permissionState = MediaPermissionState.Granted,
            notificationState = NotificationPermissionState.Denied,
        )

        // The relationship between the two asks is stated, not left to be inferred from the copy.
        composeRule.onNodeWithText("Photos & videos").assertIsDisplayed()
        composeRule.onNodeWithText("Required").assertIsDisplayed()
        composeRule.onNodeWithText("Optional").assertIsDisplayed()
    }

    @Test
    fun `the location rationale and the privacy note are shown`() {
        render()

        // ACCESS_MEDIA_LOCATION is asked for as part of media access but has no prompt of its own;
        // this line is the only place its purpose and its on-device-only guarantee are stated.
        composeRule
            .onNodeWithText(
                "Reads the location already saved in your photos — on this device, for the in-app map. It is never uploaded.",
            )
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Your library stays on your device. Nothing is uploaded until you turn on backup, and then only the media you choose.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `before the media request there is no media section but the privacy note remains`() {
        render(permissionState = MediaPermissionState.NotRequested)

        // Nothing has been asked yet, so there is no state to report; the guidance still applies.
        composeRule.onNodeWithText("Required").assertDoesNotExist()
        composeRule.onNodeWithText("Photos & videos").assertDoesNotExist()
        composeRule
            .onNodeWithText(
                "Your library stays on your device. Nothing is uploaded until you turn on backup, and then only the media you choose.",
            )
            .assertIsDisplayed()
    }
}
