package com.telepic.ui.onboarding

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepic.domain.media.Album
import com.telepic.onboarding.BackupPreference
import com.telepic.telegram.TelegramAuthController
import com.telepic.telegram.TelegramAuthState
import com.telepic.ui.onboarding.components.TAG_FOLDER_PICKER_CONFIRM
import com.telepic.ui.onboarding.components.TAG_ONBOARDING_PRIMARY
import com.telepic.ui.onboarding.components.TAG_ONBOARDING_SECONDARY
import com.telepic.ui.onboarding.steps.TAG_BACKUP_ALL
import com.telepic.ui.onboarding.steps.TAG_BACKUP_FOLDER
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
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
 * details. The Telegram step uses a fake, unavailable controller so the flow never depends on
 * a real backend. Permission and Telegram steps are advanced with their secondary actions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class OnboardingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Fake fallback controller: honest "not connected", backend unavailable, no-op actions. */
    private class FakeTelegramController : TelegramAuthController {
        private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        override val state: StateFlow<TelegramAuthState> = _state.asStateFlow()
        override val isBackendAvailable: Boolean = false
        override fun start() = Unit
        override fun submitPhoneNumber(phoneNumber: String) = Unit
        override fun submitCode(code: String) = Unit
        override fun submitPassword(password: String) = Unit
        override fun submitEmailAddress(emailAddress: String) = Unit
        override fun submitEmailCode(code: String) = Unit
        override fun resendEmailCode() = Unit
        override fun logout() = Unit
    }

    private fun folder(bucketId: Long, title: String, count: Int) = Album(
        bucketId = bucketId,
        title = title,
        coverUri = Uri.parse("content://media/cover/$bucketId"),
        coverIsVideo = false,
        count = count,
        latestMillis = 1L,
    )

    private val deviceFolders = listOf(folder(11L, "Camera", 42), folder(22L, "Screenshots", 7))

    @Test
    fun `welcome shows branding and a primary action`() {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                OnboardingScreen(
                    backupPreference = null,
                    backupBucketIds = emptySet(),
                    folders = emptyList(),
                    telegramController = FakeTelegramController(),
                    onBackupPreferenceChange = {},
                    onNeedFolders = {},
                    onPickFolders = {},
                    onComplete = {},
                )
            }
        }
        composeRule.onNodeWithText("Telepic").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).assertIsDisplayed()
    }

    @Test
    fun `backup choice selection is reported to the host`() {
        var chosen: BackupPreference? = null
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                OnboardingScreen(
                    backupPreference = null,
                    backupBucketIds = emptySet(),
                    folders = emptyList(),
                    telegramController = FakeTelegramController(),
                    onBackupPreferenceChange = { chosen = it },
                    onNeedFolders = {},
                    onPickFolders = {},
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
    fun `folder choice opens the picker and confirming reports the picked buckets`() {
        var requestedFolders = false
        var picked: Set<Long>? = null
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                OnboardingScreen(
                    backupPreference = null,
                    backupBucketIds = emptySet(),
                    folders = deviceFolders,
                    telegramController = FakeTelegramController(),
                    onBackupPreferenceChange = {},
                    onNeedFolders = { requestedFolders = true },
                    onPickFolders = { picked = it },
                    onComplete = {},
                )
            }
        }
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> How
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> Permissions
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Telegram (skip)
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Backup

        // The folder choice is now actionable and asks the host for the live folder list.
        composeRule.onNodeWithTag(TAG_BACKUP_FOLDER).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_BACKUP_FOLDER).performClick()
        assertTrue(requestedFolders)

        composeRule.onNodeWithText("Choose folders").assertIsDisplayed()
        // Nothing picked yet: confirming an empty selection must stay impossible.
        composeRule.onNodeWithTag(TAG_FOLDER_PICKER_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithText("Camera").performClick()
        composeRule.onNodeWithTag(TAG_FOLDER_PICKER_CONFIRM).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_FOLDER_PICKER_CONFIRM).performClick()
        assertEquals(setOf(11L), picked)
    }

    @Test
    fun `full flow completes into the main application`() {
        var completed = false
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                FlowHost(onCompleted = { completed = true })
            }
        }

        composeRule.onNodeWithText("Telepic").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick()
        composeRule.onNodeWithText("How Telepic works").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick()
        composeRule.onNodeWithText("Permissions").assertIsDisplayed()
        // Both asks live on this screen: media (the scaffold's primary CTA) and notifications (its
        // own card). The section title is state-independent, so this holds whether the host reports
        // notifications as granted or denied. Asserted as existence, not visibility: the step scrolls
        // and at this class's default viewport the notification card sits below the fold.
        // PermissionsStepTest covers the card being reachable and clickable.
        composeRule.onNodeWithText("Notifications").assertExists()

        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick()
        composeRule.onNodeWithText("Not connected").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick()
        composeRule.onNodeWithText("Choose your backup").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_BACKUP_ALL).performClick()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick()
        composeRule.onNodeWithText("You're ready to use Telepic").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ONBOARDING_START).performClick()
        composeRule.onNodeWithText("MAIN_APP").assertIsDisplayed()
        assertTrue(completed)
    }

    @Test
    fun `every onboarding step title is exposed as a heading`() {
        renderOnboarding()
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

        // Heading semantics are what let TalkBack jump between steps instead of reading every
        // paragraph on the way. All six screens must expose one — including Welcome, whose title
        // is the first thing a screen-reader user lands on.
        composeRule.onNodeWithText("Telepic").assert(isHeading)
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> How it works
        composeRule.onNodeWithText("How Telepic works").assert(isHeading)
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> Permissions
        composeRule.onNodeWithText("Permissions").assert(isHeading)
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Telegram (skip)
        composeRule.onNodeWithText("Connect Telegram").assert(isHeading)
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Backup
        composeRule.onNodeWithText("Choose your backup").assert(isHeading)
        composeRule.onNodeWithTag(TAG_BACKUP_ALL).performClick()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> Ready
        composeRule.onNodeWithText("You're ready to use Telepic").assert(isHeading)
    }

    @Test
    fun `the backup cards form a single selectable group`() {
        renderOnboarding()
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> How it works
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).performClick() // -> Permissions
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Telegram (skip)
        composeRule.onNodeWithTag(TAG_ONBOARDING_SECONDARY).performClick() // -> Backup

        // Exactly one group, so the three cards are announced as "1 of 3" rather than as three
        // unrelated radio buttons.
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .assertCountEquals(1)
    }

    /**
     * Renders the real six-step flow with the honest, backend-unavailable Telegram fallback, and
     * hosts the backup choice the way a real host would. The selection has to round-trip: with a
     * no-op callback the preference never becomes non-null, Continue stays disabled on the backup
     * step, and the Ready step is unreachable.
     */
    private fun renderOnboarding() {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                var backup by remember { mutableStateOf<BackupPreference?>(null) }
                OnboardingScreen(
                    backupPreference = backup,
                    backupBucketIds = emptySet(),
                    folders = emptyList(),
                    telegramController = FakeTelegramController(),
                    onBackupPreferenceChange = { backup = it },
                    onNeedFolders = {},
                    onPickFolders = {},
                    onComplete = {},
                )
            }
        }
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
                backupBucketIds = emptySet(),
                folders = emptyList(),
                telegramController = FakeTelegramController(),
                onBackupPreferenceChange = { backup = it },
                onNeedFolders = {},
                onPickFolders = { backup = BackupPreference.SELECT_FOLDER },
                onComplete = {
                    done = true
                    onCompleted()
                },
            )
        }
    }
}
