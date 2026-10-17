package com.telepic.ui.screens.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.telepic.navigation.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The video surface's swipe contract, driven on the JVM. `VideoViewer` itself needs a real ExoPlayer
 * and cannot run under Robolectric, so the gesture lives in [swipeToNavigate] and is pinned here:
 * left pages to the older neighbour ([next]), right to the newer one ([previous]), and anything under
 * the [SwipeResolver] threshold — or a missing neighbour at the end of the set — does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class SwipeToNavigateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val previous = MediaSource.Local(6L)
    private val next = MediaSource.Local(4L)

    private fun surface(
        previous: MediaSource? = this.previous,
        next: MediaSource? = this.next,
        onNavigate: (MediaSource) -> Unit,
    ) {
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(TAG)
                    .swipeToNavigate(key = null, previous = previous, next = next, onNavigate = onNavigate),
            )
        }
    }

    @Test
    fun `swiping left pages to the older neighbour`() {
        var navigated: MediaSource? = null
        surface(onNavigate = { navigated = it })

        composeRule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(next, navigated)
    }

    @Test
    fun `swiping right pages to the newer neighbour`() {
        var navigated: MediaSource? = null
        surface(onNavigate = { navigated = it })

        composeRule.onNodeWithTag(TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(previous, navigated)
    }

    @Test
    fun `a short drag is below the threshold and does not navigate`() {
        var navigated: MediaSource? = null
        surface(onNavigate = { navigated = it })

        composeRule.onNodeWithTag(TAG).performTouchInput {
            swipe(start = center, end = center.copy(x = center.x - width * 0.1f))
        }
        composeRule.waitForIdle()

        assertNull(navigated)
    }

    @Test
    fun `swiping toward a missing neighbour at the end of the set does not navigate`() {
        var navigated: MediaSource? = null
        surface(previous = null, next = null, onNavigate = { navigated = it })

        composeRule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertNull(navigated)
    }

    private companion object {
        const val TAG = "swipe_surface"
    }
}
