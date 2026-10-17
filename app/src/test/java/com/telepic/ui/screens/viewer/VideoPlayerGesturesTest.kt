package com.telepic.ui.screens.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.telepic.navigation.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The video surface's whole gesture contract, driven on the JVM. `VideoViewer` itself needs a real
 * ExoPlayer and cannot run under Robolectric, so the gesture lives in [videoPlayerGestures] and is
 * pinned here against captured callbacks: horizontal still pages, a tap toggles the chrome, a
 * double-tap seeks (centre plays/pauses), a vertical drag tunes a level, and a long-press boosts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class VideoPlayerGesturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val previous = MediaSource.Local(6L)
    private val next = MediaSource.Local(4L)

    private class Recorder {
        var navigated: MediaSource? = null
        var toggleCount = 0
        var centerTaps = 0
        val seeks = mutableListOf<Long>()
        val speedCalls = mutableListOf<Boolean>()
        val levels = mutableListOf<Pair<LevelKind, Float>>()
    }

    private fun surface(
        previous: MediaSource? = this.previous,
        next: MediaSource? = this.next,
        recorder: Recorder,
    ) {
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(TAG)
                    .videoPlayerGestures(
                        key = null,
                        previous = previous,
                        next = next,
                        onNavigate = { recorder.navigated = it },
                        onToggleControls = { recorder.toggleCount++ },
                        onDoubleTapCenter = { recorder.centerTaps++ },
                        onSeekBy = { recorder.seeks += it },
                        onSpeedBoost = { recorder.speedCalls += it },
                        onLevel = { kind, delta -> recorder.levels += kind to delta },
                    ),
            )
        }
    }

    @Test
    fun `swiping left pages to the older neighbour`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(next, recorder.navigated)
    }

    @Test
    fun `swiping right pages to the newer neighbour`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(previous, recorder.navigated)
    }

    @Test
    fun `a short drag does not navigate`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput {
            swipe(start = center, end = center.copy(x = center.x - width * 0.1f))
        }
        composeRule.waitForIdle()

        assertNull(recorder.navigated)
    }

    @Test
    fun `a single tap toggles the chrome`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { click(center) }
        // A tap is held back until the double-tap window closes, so let the clock run past it.
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        assertEquals(1, recorder.toggleCount)
    }

    @Test
    fun `a double-tap on the left seeks backward`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { doubleClick(Offset(width * 0.15f, height / 2f)) }
        composeRule.waitForIdle()

        assertEquals(listOf(-VideoGestureMath.DOUBLE_TAP_SEEK_MS), recorder.seeks)
        assertEquals(0, recorder.centerTaps)
    }

    @Test
    fun `a double-tap on the right seeks forward`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { doubleClick(Offset(width * 0.85f, height / 2f)) }
        composeRule.waitForIdle()

        assertEquals(listOf(VideoGestureMath.DOUBLE_TAP_SEEK_MS), recorder.seeks)
    }

    @Test
    fun `a double-tap in the centre toggles playback`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()

        assertEquals(1, recorder.centerTaps)
        assertTrue(recorder.seeks.isEmpty())
    }

    @Test
    fun `a vertical drag up on the right half raises the volume`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput {
            swipe(start = Offset(width * 0.8f, height * 0.8f), end = Offset(width * 0.8f, height * 0.2f))
        }
        composeRule.waitForIdle()

        assertTrue(recorder.levels.isNotEmpty())
        assertTrue(recorder.levels.all { it.first == LevelKind.VOLUME })
        assertTrue(recorder.levels.sumOf { it.second.toDouble() } > 0.0)
        assertEquals(0, recorder.toggleCount)
    }

    @Test
    fun `a vertical drag down on the left half lowers the brightness`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput {
            swipe(start = Offset(width * 0.2f, height * 0.2f), end = Offset(width * 0.2f, height * 0.8f))
        }
        composeRule.waitForIdle()

        assertTrue(recorder.levels.isNotEmpty())
        assertTrue(recorder.levels.all { it.first == LevelKind.BRIGHTNESS })
        assertTrue(recorder.levels.sumOf { it.second.toDouble() } < 0.0)
    }

    @Test
    fun `a long press boosts and releasing restores`() {
        val recorder = Recorder()
        surface(recorder = recorder)

        composeRule.onNodeWithTag(TAG).performTouchInput { longClick(center) }
        composeRule.waitForIdle()

        assertEquals(listOf(true, false), recorder.speedCalls)
    }

    private companion object {
        const val TAG = "gesture_surface"
    }
}
