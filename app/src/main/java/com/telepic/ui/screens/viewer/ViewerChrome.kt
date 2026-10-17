package com.telepic.ui.screens.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.telepic.ui.theme.TelepicTokens

/**
 * The viewer's "glass" control language: a soft translucent surface with a hairline edge, so controls
 * read as distinct tappable chips floating over the photo instead of as bare white glyphs.
 *
 * Scheme-independent, like the other on-media tokens: the viewer backdrop is black in both schemes, so
 * the veil is the same translucent black either way. It is deliberately lighter than `chromeScrim` —
 * that scrim only has to keep static text legible, while a control has to *look* like a control.
 */

/** Fully-rounded ends for a pill that groups several controls onto one glass surface. */
private val GlassPillShape = RoundedCornerShape(percent = 50)

/**
 * Applies the glass fill + hairline border, clipped to [shape]. A `Modifier` rather than a wrapper box
 * so callers keep their own layout (a single icon, a row of them, or a sized slot).
 */
@Composable
internal fun Modifier.glassSurface(shape: Shape): Modifier {
    val colors = TelepicTokens.colors
    return this
        .clip(shape)
        .background(colors.glassFill, shape)
        .border(1.dp, colors.glassBorder, shape)
}

/**
 * A single circular glass button — the back arrow, the video mute toggle, the video's save control.
 * Keeps the 48dp touch target and the ripple clipped to the circle.
 */
@Composable
internal fun GlassIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(TelepicTokens.spacing.touchTarget)
            .glassSurface(CircleShape),
    ) {
        Icon(imageVector = imageVector, contentDescription = contentDescription, tint = tint)
    }
}

/**
 * A glass pill that groups related controls onto one surface (the viewer's star/backup/overflow, the
 * photo navigation arrows). The thin horizontal padding keeps the outer buttons off the rounded end.
 */
@Composable
internal fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .glassSurface(GlassPillShape)
            .padding(horizontal = TelepicTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A large circular glass medallion holding one icon, for the viewer's empty/error states: it gives the
 * state a designed centre of gravity instead of a raw 40dp glyph floating on black.
 */
@Composable
internal fun GlassMedallion(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .glassSurface(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = imageVector, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
    }
}
