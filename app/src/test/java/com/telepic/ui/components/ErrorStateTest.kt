package com.telepic.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
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
 * The error state's Retry button must be its own focus stop.
 *
 * It used to sit inside the same `mergeDescendants` block as the title and the explanation, and a
 * merged semantics node keeps a single `onClick` — the last child's. The whole card therefore
 * became one giant retry target, and double-tapping the *title* fired a retry. `EmptyState` had
 * exactly this bug and was fixed; `ErrorState` is used in seven places and was missed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ErrorStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the retry button is reachable and runs the retry`() {
        var retries = 0
        composeRule.setContent {
            TelepicTheme {
                ErrorState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Could not open the library",
                    explanation = "The provider refused to answer.",
                    onRetry = { retries++ },
                )
            }
        }

        composeRule.onNodeWithText("Try again").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals("the retry must run exactly once", 1, retries)
    }

    @Test
    fun `the title and the explanation are still readable`() {
        composeRule.setContent {
            TelepicTheme {
                ErrorState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Could not open the library",
                    explanation = "The provider refused to answer.",
                )
            }
        }

        composeRule.onNodeWithText("Could not open the library").assertIsDisplayed()
        composeRule.onNodeWithText("The provider refused to answer.").assertIsDisplayed()
    }
}
