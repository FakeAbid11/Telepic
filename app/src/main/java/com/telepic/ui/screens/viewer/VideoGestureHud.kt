package com.telepic.ui.screens.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The transient confirmation a video gesture leaves on screen: the omni-present counterpart of the
 * chrome. Everything here is a *response* to a gesture the surface already handled — the seek jump, the
 * brightness/volume level, the speed boost — so the user is never guessing whether the swipe registered.
 *
 * Drawn on the same glass surface as the rest of the viewer's controls, so a bare black pill never
 * appears over a bright frame. Pure layout + glyphs, no `Player`, so it composes and asserts without a
 * device. [feedback] is null most of the time; the caller owns how long each value lingers.
 */
@Composable
internal fun VideoGestureHud(feedback: VideoGestureFeedback?, modifier: Modifier = Modifier) {
    // A full-size, non-interactive overlay. It carries no `pointerInput`, so touches fall straight
    // through to the gesture surface beneath it.
    Box(modifier = modifier.fillMaxSize()) {
        when (feedback) {
            is VideoGestureFeedback.Seek -> SeekFeedback(
                forward = feedback.forward,
                modifier = Modifier
                    .align(if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = TelepicTokens.spacing.xl),
            )
            is VideoGestureFeedback.Level -> LevelFeedback(
                kind = feedback.kind,
                fraction = feedback.fraction,
                modifier = Modifier.align(Alignment.Center),
            )
            is VideoGestureFeedback.SpeedBoost -> SpeedFeedback(
                multiplier = feedback.multiplier,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Below the **whole** top bar: the bar is the status-bar inset plus the chrome
                    // row, and the chrome row alone left the top of this HUD behind it — the bar is
                    // composed after the HUD, so it drew on top.
                    .padding(top = TelepicTokens.spacing.chromeBarHeight)
                    .windowInsetsPadding(WindowInsets.statusBars),
            )
            null -> Unit
        }
    }
}

@Composable
private fun SeekFeedback(forward: Boolean, modifier: Modifier = Modifier) {
    val description = stringResource(
        if (forward) R.string.viewer_video_seek_forward else R.string.viewer_video_seek_backward,
    )
    Row(
        modifier = modifier
            .glassSurface(RoundedCornerShape(percent = 50))
            .padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!forward) SeekChevrons(pointsRight = false)
        Text(
            text = "${VideoGestureMath.DOUBLE_TAP_SEEK_MS / 1000} s",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        if (forward) SeekChevrons(pointsRight = true)
    }
}

@Composable
private fun LevelFeedback(kind: LevelKind, fraction: Float, modifier: Modifier = Modifier) {
    val label = stringResource(
        if (kind == LevelKind.BRIGHTNESS) R.string.viewer_video_brightness else R.string.viewer_video_volume,
    )
    val clamped = VideoGestureMath.clampLevel(fraction)
    val percent = (clamped * 100f).roundToInt()
    Row(
        modifier = modifier
            .width(200.dp)
            .glassSurface(RoundedCornerShape(percent = 50))
            .padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm)
            .semantics { contentDescription = "$label $percent%" },
        horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == LevelKind.BRIGHTNESS) {
            SunGlyph()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clamped)
                    .background(Color.White),
            )
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpeedFeedback(multiplier: Float, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.viewer_video_speed_boost)
    Row(
        modifier = modifier
            .glassSurface(RoundedCornerShape(percent = 50))
            .padding(horizontal = TelepicTokens.spacing.md, vertical = TelepicTokens.spacing.sm)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${multiplier.roundToInt()}×",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** Two chevrons pointing toward the seek direction — drawn, so no extended icon dependency. */
@Composable
private fun SeekChevrons(pointsRight: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 26.dp, height = 20.dp)) {
        val stroke = size.height * 0.14f
        val halfHeight = size.height * 0.5f
        val chevronWidth = size.width * 0.3f
        val cy = center.y
        repeat(2) { index ->
            val cx = size.width * (0.3f + index * 0.4f)
            val tipX = if (pointsRight) cx + chevronWidth / 2f else cx - chevronWidth / 2f
            val backX = if (pointsRight) cx - chevronWidth / 2f else cx + chevronWidth / 2f
            drawLine(
                color = Color.White,
                start = Offset(backX, cy - halfHeight / 2f),
                end = Offset(tipX, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = Offset(backX, cy + halfHeight / 2f),
                end = Offset(tipX, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The brightness glyph: a filled disc with eight rays — a sun, drawn so no icon set is needed. */
@Composable
private fun SunGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val c = center
        val radius = size.minDimension * 0.2f
        drawCircle(color = Color.White, radius = radius, center = c)
        val inner = radius * 1.5f
        val outer = size.minDimension * 0.44f
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = Color.White,
                start = Offset(c.x + dx * inner, c.y + dy * inner),
                end = Offset(c.x + dx * outer, c.y + dy * outer),
                strokeWidth = size.minDimension * 0.09f,
                cap = StrokeCap.Round,
            )
        }
    }
}
