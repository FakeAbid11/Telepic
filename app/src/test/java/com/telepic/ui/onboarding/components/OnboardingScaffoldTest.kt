package com.telepic.ui.onboarding.components

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.telepic.ui.theme.TelepicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The onboarding primary action is sized with a *minimum* height, not a fixed one. A fixed
 * `height(52.dp)` kept the touch target but clipped a label that wrapped to a second line at a
 * larger font scale — an accessibility regression that only shows up on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class OnboardingScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(primaryLabel: String) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                OnboardingScaffold(
                    stepIndex = 0,
                    totalSteps = 6,
                    progressLabel = "Step 1 of 6",
                    primaryLabel = primaryLabel,
                    onPrimaryAction = {},
                    content = {},
                )
            }
        }
    }

    @Test
    fun `the primary action keeps a 52dp touch target`() {
        render("Continue")
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).assertHeightIsAtLeast(52.dp)
    }

    @Test
    fun `a wrapped label grows the primary action instead of being clipped`() {
        // Comfortably longer than one line at the default test width. Under a fixed height this
        // stays pinned at 52dp and the second line is clipped; under a minimum it grows.
        render("Back up all of my photos and videos to my private Telegram channel right now")
        composeRule.onNodeWithTag(TAG_ONBOARDING_PRIMARY).assertHeightIsAtLeast(53.dp)
    }
}
