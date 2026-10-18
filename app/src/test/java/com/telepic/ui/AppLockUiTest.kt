package com.telepic.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepic.ui.theme.TelepicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-level coverage for the app-lock gate: it states what it is waiting for, reports through
 * immediately when the host offers no system credential prompt (the JVM test host is a plain
 * ComponentActivity, not a FragmentActivity), and the recovery screen explains an unreadable lock
 * state instead of showing the library.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AppLockUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(onUnlocked: () -> Unit = {}) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                AppLockScreen(onUnlocked = onUnlocked)
            }
        }
    }

    @Test
    fun `lock screen states what it is waiting for`() {
        render()
        composeRule.onNodeWithText("Telepic is locked").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertIsDisplayed()
    }

    @Test
    fun `unlock reports back when the host has no system prompt`() {
        var unlocked = 0
        render(onUnlocked = { unlocked++ })
        composeRule.waitForIdle()
        // The screen prompts on open; without a FragmentActivity host there is no system prompt to
        // show, so the only honest outcome is that it reports through at once.
        assertEquals(1, unlocked)
        composeRule.onNodeWithText("Unlock").performClick()
        assertEquals(2, unlocked)
    }

    @Test
    fun `recovery screen explains an unreadable lock state and offers retry`() {
        var retries = 0
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                AppLockRecoveryScreen(onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithText("App lock state unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }
}
