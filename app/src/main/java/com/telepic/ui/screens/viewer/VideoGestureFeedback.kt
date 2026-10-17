package com.telepic.ui.screens.viewer

/**
 * Which system level a vertical drag on the video surface tunes: the left half is brightness, the
 * right half is volume — the convention NextPlayer and most video players share, so a user's muscle
 * memory carries over.
 */
internal enum class LevelKind { BRIGHTNESS, VOLUME }

/**
 * Where a double-tap landed. The outer thirds seek, the middle plays/pauses — a double-tap on the
 * left of the picture rewinds, on the right fast-forwards, in the centre toggles playback.
 */
internal enum class DoubleTapZone { BACKWARD, FORWARD, CENTER }

/**
 * A transient, on-media confirmation of a video gesture, drawn briefly by [VideoGestureHud]. Plain
 * data (no `Player`, no `AudioManager`) so the HUD and the values it prints are testable on the JVM.
 */
internal sealed interface VideoGestureFeedback {
    /** A double-tap seek of [deltaMs], signed; [forward] drives which way the chevrons point. */
    data class Seek(val deltaMs: Long, val forward: Boolean) : VideoGestureFeedback

    /** A brightness/volume drag that landed on [fraction] of that level's range. */
    data class Level(val kind: LevelKind, val fraction: Float) : VideoGestureFeedback

    /** The temporary speed boost while a long-press is held. */
    data class SpeedBoost(val multiplier: Float) : VideoGestureFeedback
}

/**
 * The arithmetic behind the video gestures, kept pure so the zones, the sides and the level mapping
 * are pinned by tests rather than by a finger on a device. Every function takes the surface's own
 * size, so nothing here reaches for a player, a window or an audio stream.
 */
internal object VideoGestureMath {
    /** How far a double-tap jumps the clock. */
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    /** The multiplier applied while the long-press speed gesture is held. */
    const val SPEED_BOOST_MULTIPLIER = 2f

    /**
     * Fraction of the width that counts as the centre play/pause zone. A third each side leaves the
     * outer thirds — where a thumb naturally lands — for seeking.
     */
    const val CENTER_ZONE_FRACTION = 1f / 3f

    /** Where a double-tap at [x] (0..[width]) falls. Degenerate widths fall back to play/pause. */
    fun doubleTapZone(x: Float, width: Float): DoubleTapZone {
        if (width <= 0f) return DoubleTapZone.CENTER
        val edge = width * CENTER_ZONE_FRACTION
        return when {
            x < edge -> DoubleTapZone.BACKWARD
            x > width - edge -> DoubleTapZone.FORWARD
            else -> DoubleTapZone.CENTER
        }
    }

    /** Left half tunes brightness, right half volume. Degenerate widths default to volume. */
    fun levelKindFor(x: Float, width: Float): LevelKind =
        if (width > 0f && x < width / 2f) LevelKind.BRIGHTNESS else LevelKind.VOLUME

    /**
     * The signed change to apply to the current level fraction for a vertical drag of [dy] over a
     * surface [height]. Dragging **up** (negative `dy`) raises the level, and a full-height drag
     * sweeps the whole 0..1 range.
     */
    fun levelDelta(dy: Float, height: Float): Float =
        if (height <= 0f) 0f else -(dy / height)

    /** Keeps a level inside 0..1 — a drag cannot push brightness below off or volume past maximum. */
    fun clampLevel(fraction: Float): Float = fraction.coerceIn(0f, 1f)
}
