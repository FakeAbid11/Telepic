package com.telepic.ui.screens.settings

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.telepic.ui.theme.TelepicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a settings row tells a screen reader. A row with no action is plain information: it is neither
 * clickable nor disabled, and announces no state at all. Attaching `clickable` unconditionally made an
 * informational row publish `Disabled` while it sat at full contrast — reading as "broken" rather than
 * "not a control". Only a row that *has* an action can be switched off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SettingsRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val title = "Backup quality"

    private fun row(enabled: Boolean = true, onClick: (() -> Unit)? = null) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                SettingsRow(title = title, summary = "Original", enabled = enabled, onClick = onClick)
            }
        }
    }

    @Test
    fun `a row with no action is neither disabled nor clickable`() {
        row(onClick = null)

        // assertIsEnabled() is exactly "the Disabled property is absent" — the regression was that
        // this property got published for a row that has no action to disable in the first place.
        composeRule.onNodeWithText(title).assertIsEnabled()
        composeRule.onNode(hasText(title) and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `a switched-off action is announced as disabled`() {
        row(enabled = false, onClick = {})

        composeRule.onNodeWithText(title).assertIsNotEnabled()
    }

    @Test
    fun `an enabled action stays clickable`() {
        row(enabled = true, onClick = {})

        composeRule.onNodeWithText(title).assertHasClickAction()
    }
}
