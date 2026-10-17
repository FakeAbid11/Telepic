package com.telepic.ui.screens.viewer

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.telepic.data.restore.RestoreFailure
import com.telepic.ui.theme.TelepicTheme
import com.telepic.ui.theme.TelepicTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage of the video transport bar. `VideoViewer` itself stays device-only (it builds
 * a real ExoPlayer), but the bar it draws takes plain state and callbacks, so every control on it —
 * including the seek mapping and the cloud save action — is assertable on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class VideoTransportUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The transport's own height token. Captured during composition because `TelepicTokens` is a
     * `@ReadOnlyComposable` accessor — the same reason `ViewerUiTest` reads the bar expectation back
     * inside `setContent` rather than outside it.
     */
    private var expectedBarHeight = 0.dp

    private fun transport(
        state: VideoTransportState = VideoTransportState(
            isPlaying = false,
            positionMs = 30_000L,
            durationMs = 60_000L,
        ),
        restore: RestoreState = RestoreState.Idle,
        onRestore: (() -> Unit)? = null,
        muted: Boolean = true,
        onToggleMute: () -> Unit = {},
        onTogglePlay: () -> Unit = {},
        onSeekTo: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            expectedBarHeight = TelepicTokens.spacing.videoTransportHeight
            TelepicTheme {
                VideoTransport(
                    state = state,
                    onTogglePlay = onTogglePlay,
                    onSeekTo = onSeekTo,
                    muted = muted,
                    onToggleMute = onToggleMute,
                    onRestore = onRestore,
                    restore = restore,
                )
            }
        }
    }

    @Test
    fun `the transport is exactly its height token`() {
        transport()
        // The bar is now drawn *over* a full-bleed video rather than inside a reserved band, but its
        // height still comes from `TelepicSpacing.videoTransportHeight`: too tall and it would swallow
        // more of the picture than designed, too short and its two tiers would clip.
        composeRule.onNodeWithTag(TAG_VIDEO_TRANSPORT)
            .assertHeightIsEqualTo(expectedBarHeight)
    }

    @Test
    fun `a paused video offers play and a playing video offers pause`() {
        var toggled = false
        transport(onTogglePlay = { toggled = true })

        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        assertTrue(toggled)
    }

    @Test
    fun `the control labels name the action, not the state`() {
        // Playing → the glyph is two bars, but the button must still announce "Pause".
        transport(state = VideoTransportState(isPlaying = true, positionMs = 1_000, durationMs = 60_000))

        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Play").assertCountEquals(0)
    }

    @Test
    fun `the mute control rides the transport and names the action it performs`() {
        var toggled = false
        transport(muted = true, onToggleMute = { toggled = true })

        // Muted → the button offers to unmute (the action, not the state), and tapping it flips it.
        composeRule.onNodeWithContentDescription("Unmute").assertIsDisplayed().performClick()
        assertTrue(toggled)
    }

    @Test
    fun `an unmuted video offers to mute`() {
        transport(muted = false)
        composeRule.onNodeWithContentDescription("Mute").assertIsDisplayed()
    }

    @Test
    fun `the clock shows both the position and the duration`() {
        transport()
        composeRule.onNodeWithText("0:30").assertIsDisplayed()
        composeRule.onNodeWithText("1:00").assertIsDisplayed()
    }

    @Test
    fun `dragging the seek bar seeks to the dragged position`() {
        var seekTarget = -1L
        transport(onSeekTo = { seekTarget = it })

        composeRule.onNodeWithContentDescription("Video position")
            .performSemanticsAction(SemanticsActions.SetProgress) { it.invoke(0.25f) }

        // A quarter of the 60s duration, sought while dragging rather than on release.
        assertEquals(15_000L, seekTarget)
        // The clock following the scrub is arithmetic, not a gesture: it is pinned in
        // `VideoTransportTest`. No gesture available here holds a drag open long enough to read it —
        // Material3's slider semantics finishes the gesture as it reports the target.
    }

    @Test
    fun `an unknown duration leaves the seek bar unusable instead of snapping to a lie`() {
        var seekTarget = -1L
        transport(
            state = VideoTransportState(isPlaying = true, positionMs = 0L, durationMs = 0L),
            onSeekTo = { seekTarget = it },
        )

        composeRule.onNodeWithContentDescription("Video position").assertIsNotEnabled()
        composeRule.onNodeWithText("0:00").assertIsDisplayed()
        assertEquals(-1L, seekTarget)
    }

    @Test
    fun `a cloud video carries its save action on the same bar`() {
        var saved = false
        transport(onRestore = { saved = true })

        composeRule.onNodeWithContentDescription("Save to device").assertIsDisplayed().performClick()
        assertTrue(saved)
    }

    @Test
    fun `a failed save keeps its reason reachable`() {
        transport(
            onRestore = {},
            restore = RestoreState.Failed(RestoreFailure.LOW_STORAGE),
        )
        // A red icon alone cannot tell "no space" from "not available" apart, so the reason rides on
        // the content description.
        composeRule.onNodeWithContentDescription("Not enough storage space").assertIsDisplayed()
    }
}
