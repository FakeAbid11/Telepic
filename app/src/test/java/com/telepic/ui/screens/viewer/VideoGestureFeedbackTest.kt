package com.telepic.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video gestures' arithmetic, pinned on the JVM: which double-tap zone a touch falls in, which
 * half tunes brightness vs volume, and how a vertical drag maps to a level. No Compose, no Robolectric
 * — these are the rules the on-device gesture detector is built from, and they must not drift.
 */
class VideoGestureFeedbackTest {

    private val width = 300f
    private val height = 600f

    @Test
    fun `double-tap on the left third seeks backward`() {
        assertEquals(DoubleTapZone.BACKWARD, VideoGestureMath.doubleTapZone(0f, width))
        assertEquals(DoubleTapZone.BACKWARD, VideoGestureMath.doubleTapZone(width / 3f - 1f, width))
    }

    @Test
    fun `double-tap on the right third seeks forward`() {
        assertEquals(DoubleTapZone.FORWARD, VideoGestureMath.doubleTapZone(width, width))
        assertEquals(DoubleTapZone.FORWARD, VideoGestureMath.doubleTapZone(width * 2f / 3f + 1f, width))
    }

    @Test
    fun `double-tap in the middle toggles playback`() {
        assertEquals(DoubleTapZone.CENTER, VideoGestureMath.doubleTapZone(width / 2f, width))
        assertEquals(DoubleTapZone.CENTER, VideoGestureMath.doubleTapZone(width / 3f, width))
    }

    @Test
    fun `a zero width cannot divide the zones and falls back to the centre`() {
        assertEquals(DoubleTapZone.CENTER, VideoGestureMath.doubleTapZone(0f, 0f))
    }

    @Test
    fun `the left half tunes brightness and the right half volume`() {
        assertEquals(LevelKind.BRIGHTNESS, VideoGestureMath.levelKindFor(0f, width))
        assertEquals(LevelKind.BRIGHTNESS, VideoGestureMath.levelKindFor(width / 2f - 1f, width))
        assertEquals(LevelKind.VOLUME, VideoGestureMath.levelKindFor(width / 2f, width))
        assertEquals(LevelKind.VOLUME, VideoGestureMath.levelKindFor(width, width))
    }

    @Test
    fun `dragging up raises the level and dragging down lowers it`() {
        // A move up is a negative dy; the delta must come back positive.
        assertTrue(VideoGestureMath.levelDelta(-height / 4f, height) > 0f)
        assertTrue(VideoGestureMath.levelDelta(height / 4f, height) < 0f)
    }

    @Test
    fun `a full-height drag sweeps the whole range`() {
        assertEquals(1.0, VideoGestureMath.levelDelta(-height, height).toDouble(), 0.0001)
        assertEquals(-1.0, VideoGestureMath.levelDelta(height, height).toDouble(), 0.0001)
    }

    @Test
    fun `a zero height drag cannot divide and does nothing`() {
        assertEquals(0.0, VideoGestureMath.levelDelta(-100f, 0f).toDouble(), 0.0001)
    }

    @Test
    fun `levels clamp to 0 and 1`() {
        assertEquals(0.0, VideoGestureMath.clampLevel(-0.4f).toDouble(), 0.0001)
        assertEquals(1.0, VideoGestureMath.clampLevel(1.7f).toDouble(), 0.0001)
        assertEquals(0.42, VideoGestureMath.clampLevel(0.42f).toDouble(), 0.0001)
    }
}
