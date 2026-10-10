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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vector-only decorative illustration: a Telepic blue gradient badge with a Material icon.
 * No stock photos or image dependencies — pure Compose shapes, per the Phase 2 design rule.
 */
@Composable
fun OnboardingIllustration(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    iconTint: Color = Color.White,
) {
    val primary = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        colors = listOf(primary, primary.copy(alpha = 0.55f)),
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
            tint = iconTint,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}
