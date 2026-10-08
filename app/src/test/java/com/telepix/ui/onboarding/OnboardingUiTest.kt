package com.telepix.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepix.onboarding.BackupPreference
import com.telepix.telegram.TelegramAuthState
import com.telepix.ui.onboarding.components.TAG_ONBOARDING_PRIMARY
import com.telepix.ui.onboarding.components.TAG_ONBOARDING_SECONDARY
import com.telepix.ui.onboarding.steps.TAG_BACKUP_ALL
import com.telepix.ui.theme.TelepixTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for the onboarding flow, run on the JVM via Robolectric (no emulator).
 *
 * Assertions key on stable screen titles and action test tags rather than implementation
 * details. The permission and Telegram steps are advanced with their secondary actions so the
 * flow never depends on a real system permission dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class OnboardingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `welcome shows branding and a primary action`() {
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                OnboardingScreen(
                    backupPreference = null,
                    telegramState = TelegramAuthState.NotConnected,
                    telegramBackendAvailable = false,
                    onTelegramConnect = {},
                    onBackupPreferenceChange = {},
                    onComplete = {},
                )
            }
        }
        composeRule.onNodeWithText("Telepix").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).assertIsDisplayed()
    }

    @Test
    fun `backup choice selection is reported to the host`() {
        var chosen: BackupPreference? = null
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                OnboardingScreen(
                    backupPreference = null,
                    telegramState = TelegramAuthState.NotConnected,
                    telegramBackendAvailable = false,
                    onTelegramConnect = {},
                    onBackupPreferenceChange = { chosen = it },
                    onComplete = {},
                )
            }
        }
        // Navigate to the backup step (Welcome -> How -> Permissions -> Telegram -> Backup).
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> How
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> Permissions
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Telegram (skip)
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Backup
        composeRule.onNodeWithText("Choose your backup").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_BACKUP_ALL).performClick()
        assertTrue(chosen == BackupPreference.BACKUP_ALL)
    }

    @Test
    fun `full flow completes into the main application`() {
        var completed = false
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                FlowHost(onCompleted = { completed = true })
            }
        }

        composeRule.onNodeWithText("Telepix").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick()
        composeRule.onNodeWithText("How Telepix works").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick()
        composeRule.onNodeWithText("Media permissions").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick()
        composeRule.onNodeWithText("Not connected").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick()
        composeRule.onNodeWithText("Choose your backup").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_BACKUP_ALL).performClick()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick()
        composeRule.onNodeWithText("You're ready to use Telepix").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_START).performClick()
        composeRule.onNodeWithText("MAIN_APP").assertIsDisplayed()
        assertTrue(completed)
    }

    /**
     * Holds the transient onboarding state a real host would provide from the ViewModel, so the
     * test can verify selection-gated navigation and the completion callback.
     */
    @androidx.compose.runtime.Composable
    private fun FlowHost(onCompleted: () -> Unit) {
        var backup by remember { mutableStateOf<BackupPreference?>(null) }
        var done by remember { mutableStateOf(false) }
        if (done) {
            Text("MAIN_APP")
        } else {
            OnboardingScreen(
                backupPreference = backup,
                telegramState = TelegramAuthState.NotConnected,
                telegramBackendAvailable = false,
                onTelegramConnect = {},
                onBackupPreferenceChange = { backup = it },
                onComplete = {
                    done = true
                    onCompleted()
                },
            )
        }
    }
}
