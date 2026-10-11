package com.telepic.ui.onboarding.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.telepic.onboarding.BackupPreference
import com.telepic.permissions.MediaPermissionState
import com.telepic.telegram.TelegramAuthState
import com.telepic.ui.theme.TelepicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose tests for the Ready summary.
 *
 * The rows mirror the *real* state (never a fabricated success), and every value is a short noun
 * phrase. That is not cosmetic: the value column shares a single row with a weighted label, so the
 * sentence-length form this screen used to ship for "a folder" squeezed the very label it was meant
 * to summarize. These tests pin the short forms in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ReadyStepTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        permissionState: MediaPermissionState = MediaPermissionState.Granted,
        telegramState: TelegramAuthState = TelegramAuthState.NotConnected,
        backupPreference: BackupPreference? = null,
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                ReadyStep(
                    permissionState = permissionState,
                    telegramState = telegramState,
                    backupPreference = backupPreference,
                )
            }
        }
    }

    @Test
    fun `a folder backup summarizes as a short value, not a sentence`() {
        render(backupPreference = BackupPreference.SELECT_FOLDER)
        composeRule.onNodeWithText("Selected folder").assertIsDisplayed()
    }

    @Test
    fun `backing up everything summarizes as all photos`() {
        render(backupPreference = BackupPreference.BACKUP_ALL)
        composeRule.onNodeWithText("All photos").assertIsDisplayed()
    }

    @Test
    fun `no backup preference reads as not configured`() {
        render(backupPreference = null)
        composeRule.onNodeWithText("Not configured").assertIsDisplayed()
    }

    @Test
    fun `the summary mirrors the real permission and telegram state`() {
        render(
            permissionState = MediaPermissionState.Partial,
            telegramState = TelegramAuthState.NotConnected,
        )
        composeRule.onNodeWithText("Limited access").assertIsDisplayed()
        composeRule.onNodeWithText("Not connected").assertIsDisplayed()
    }
}
