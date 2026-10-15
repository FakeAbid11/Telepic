package com.telepic.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
 * ScreenHeader is the single source of truth for every screen title that uses it.
 * If it loses [SemanticsProperties.Heading], TalkBack "navigate by heading" skips the title —
 * which is exactly the regression that happened when three screens hand-rolled the header.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ScreenHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `plain header exposes its title as a heading`() {
        composeRule.setContent {
            TelepicTheme {
                ScreenHeader(title = "Photos")
            }
        }
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        composeRule.onNodeWithText("Photos").assert(isHeading)
    }

    @Test
    fun `header with back button exposes its title as a heading`() {
        composeRule.setContent {
            TelepicTheme {
                ScreenHeader(title = "Album", onBack = {})
            }
        }
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        composeRule.onNodeWithText("Album").assert(isHeading)
    }

    @Test
    fun `header with trailing exposes its title as a heading`() {
        composeRule.setContent {
            TelepicTheme {
                ScreenHeader(title = "Settings", trailing = {})
            }
        }
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        composeRule.onNodeWithText("Settings").assert(isHeading)
    }
}
