package com.telepic.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video transport's arithmetic, tested as the pure functions it was made to be. The ExoPlayer
 * wiring behind them is device-validated only (the app has no androidTest source set), so everything
 * the bar *computes* is pinned here instead.
 */
class VideoTransportTest {

    // ---------------------------------------------------------------- clock

    @Test
    fun `the clock renders minutes and seconds`() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:07", formatPlaybackTime(7_400L))
        assertEquals("0:59", formatPlaybackTime(59_999L))
        assertEquals("1:05", formatPlaybackTime(65_000L))
    }

    @Test
    fun `the clock keeps the hour instead of wrapping it away`() {
        // 61 minutes as 1:01 would be a lie about a long recording.
        assertEquals("59:59", formatPlaybackTime(3_599_000L))
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
    }

    @Test
    fun `a negative position is not rendered as a negative time`() {
        assertEquals("0:00", formatPlaybackTime(-5_000L))
    }

    // ---------------------------------------------------------------- state

    @Test
    fun `progress is a clamped fraction of the known duration`() {
        assertEquals(0.5f, VideoTransportState(positionMs = 12_500, durationMs = 25_000).fraction, 1e-4f)
        assertEquals(1f, VideoTransportState(positionMs = 40_000, durationMs = 25_000).fraction, 1e-4f)
        assertEquals(0f, VideoTransportState(positionMs = 0, durationMs = 25_000).fraction, 1e-4f)
    }

    @Test
    fun `an unknown duration parks the thumb at the start and refuses to seek`() {
        val unknown = VideoTransportState(positionMs = 30_000, durationMs = 0)
        assertEquals(0f, unknown.fraction, 1e-4f)
        assertFalse(unknown.isSeekable)
        assertNull(unknown.seekTarget(0.5f))
    }

    @Test
    fun `a drag always seeks inside the media`() {
        val state = VideoTransportState(durationMs = 60_000)
        assertEquals(30_000L, state.seekTarget(0.5f))
        assertEquals(0L, state.seekTarget(-1f))
        assertEquals(60_000L, state.seekTarget(2f))
    }

    @Test
    fun `the clock reads the scrub target while dragging and the position otherwise`() {
        val state = VideoTransportState(positionMs = 30_000, durationMs = 60_000)
        // Not dragging: the last polled playback position.
        assertEquals(30_000L, state.displayPositionMs(null))
        // Dragging: the position the user is aiming at, not the one they left.
        assertEquals(0L, state.displayPositionMs(0f))
        assertEquals(15_000L, state.displayPositionMs(0.25f))
        assertEquals(60_000L, state.displayPositionMs(1f))
        // Clamped exactly as a seek is, so the clock cannot read past the end.
        assertEquals(60_000L, state.displayPositionMs(2f))
    }

    @Test
    fun `an unknown duration has no scrub target, so the clock keeps the position`() {
        val state = VideoTransportState(positionMs = 12_000, durationMs = 0)
        assertEquals(12_000L, state.displayPositionMs(0.5f))
    }

    @Test
    fun `a position past a freshly resolved duration is clamped, not drawn past the end`() {
        val playback = VideoPlaybackState()
        playback.onPositionChanged(50_000L)
        playback.onDurationChanged(30_000L)
        assertEquals(30_000L, playback.state.positionMs)
        assertEquals(30_000L, playback.state.durationMs)
    }

    @Test
    fun `a duration reported as unknown does not retro-clamp the position`() {
        val playback = VideoPlaybackState()
        playback.onDurationChanged(30_000L)
        playback.onPositionChanged(10_000L)
        // A re-seek, a new item or a stale callback all surface as TIME_UNSET → 0; the bar must not
        // conclude "the video is over".
        playback.onDurationChanged(0L)
        assertEquals(10_000L, playback.state.positionMs)
        assertEquals(0L, playback.state.durationMs)
    }

    @Test
    fun `a negative position is floored at zero`() {
        val playback = VideoPlaybackState()
        playback.onDurationChanged(60_000L)
        playback.onPositionChanged(-1_000L)
        assertEquals(0L, playback.state.positionMs)
    }

    @Test
    fun `the state holder reports play and pause transitions`() {
        val playback = VideoPlaybackState()
        assertFalse(playback.state.isPlaying)
        playback.onIsPlayingChanged(true)
        assertTrue(playback.state.isPlaying)
        playback.onIsPlayingChanged(false)
        assertFalse(playback.state.isPlaying)
    }
}
