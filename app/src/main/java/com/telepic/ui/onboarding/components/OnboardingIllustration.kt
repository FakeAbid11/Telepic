package com.telepic.ui.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vector-only decorative illustration: a Telepic blue gradient badge with a Material icon.
 * No stock photos or image dependencies — pure Compose shapes, per the Phase 2 design rule.
 *
 * The icon tint is intentionally not a parameter. It has to be [MaterialTheme.colorScheme.onPrimary]
 * to read against the badge, and the badge is the only thing this component ever paints it on, so an
 * override could only ever make things worse — the hard-coded `Color.White` default this used to
 * ship measured 2.33:1 against the dark-scheme badge.
 */
@Composable
fun OnboardingIllustration(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
) {
    val primary = MaterialTheme.colorScheme.primary
    // Both stops stay fully opaque on purpose. Fading `primary` toward transparency composites it
    // over whatever sits behind the badge, which drags the fill toward the background — that is,
    // toward the icon's own tint. The old `primary -> primary@0.55` gradient therefore collapsed to
    // 2.88:1 (dark) and 2.20:1 (light) at its far end, under the 3:1 floor WCAG 1.4.11 sets for a
    // graphic that carries a content description. Blending toward onSurface moves the opposite way
    // in both schemes, so contrast only improves across the sweep: 7.41:1 -> 8.78:1 dark,
    // 4.69:1 -> 6.79:1 light.
    val gradient = Brush.linearGradient(
        colors = listOf(primary, lerp(primary, MaterialTheme.colorScheme.onSurface, 0.25f)),
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}
