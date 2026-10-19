package com.telepic.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
 * The empty state's two actions must be two separately focusable nodes.
 *
 * They used to be merged into the surrounding text block, and a merged semantics node keeps a
 * single `onClick` — the *last* child's. The secondary action therefore swallowed the primary one,
 * and on the Cloud consent card that meant a screen-reader user double-tapping the card ran "create
 * a new channel" instead of "use this one": the one outcome the screen says Telepic must never
 * choose for the user.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `both actions are reachable and each runs its own action`() {
        var primary = 0
        var secondary = 0
        composeRule.setContent {
            TelepicTheme {
                EmptyState(
                    icon = Icons.Filled.Info,
                    title = "Nothing here yet",
                    description = "A longer explanation of the same thing.",
                    primaryAction = StateAction("Use this channel", { primary++ }),
                    secondaryAction = StateAction("Create a new one", { secondary++ }),
                )
            }
        }

        composeRule.onNodeWithText("Use this channel").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Create a new one").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals("the primary action must run", 1, primary)
        assertEquals("the secondary action must run", 1, secondary)
    }

    @Test
    fun `the title and description are announced as one block`() {
        composeRule.setContent {
            TelepicTheme {
                EmptyState(
                    icon = Icons.Filled.Info,
                    title = "Nothing here yet",
                    description = "A longer explanation of the same thing.",
                )
            }
        }
        // The title is still reachable as text, and the description still belongs to the same
        // announced block.
        composeRule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        composeRule.onNodeWithText("A longer explanation of the same thing.").assertIsDisplayed()
    }
}
