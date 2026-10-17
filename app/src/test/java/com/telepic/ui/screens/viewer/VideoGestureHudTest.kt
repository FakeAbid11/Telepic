package com.telepic.ui.screens.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gesture confirmation overlay, composed on the JVM. The HUD is pure layout over plain data, so
 * every value it prints — the seek amount, the level and its percentage, the speed — is asserted here
 * without a player or a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class VideoGestureHudTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(feedback: VideoGestureFeedback?) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) { VideoGestureHud(feedback = feedback) }
        }
    }

    @Test
    fun `a forward seek shows the jump and names it`() {
        show(VideoGestureFeedback.Seek(deltaMs = 10_000L, forward = true))

        composeRule.onNodeWithText("10 s").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Forward 10 seconds").assertIsDisplayed()
    }

    @Test
    fun `a backward seek is named as a rewind`() {
        show(VideoGestureFeedback.Seek(deltaMs = -10_000L, forward = false))

        composeRule.onNodeWithContentDescription("Back 10 seconds").assertIsDisplayed()
    }

    @Test
    fun `a brightness level shows its percentage`() {
        show(VideoGestureFeedback.Level(LevelKind.BRIGHTNESS, 0.4f))

        composeRule.onNodeWithText("40%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Brightness 40%").assertIsDisplayed()
    }

    @Test
    fun `a volume level shows its percentage`() {
        show(VideoGestureFeedback.Level(LevelKind.VOLUME, 0.75f))

        composeRule.onNodeWithText("75%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Volume 75%").assertIsDisplayed()
    }

    @Test
    fun `a level above one still reads as full`() {
        show(VideoGestureFeedback.Level(LevelKind.VOLUME, 1.6f))

        composeRule.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun `a speed boost shows the multiplier`() {
        show(VideoGestureFeedback.SpeedBoost(2f))

        composeRule.onNodeWithText("2×").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Playing at 2× speed").assertIsDisplayed()
    }
}
