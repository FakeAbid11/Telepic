package com.telepix.ui.onboarding.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Subtle six-dot progress indicator for onboarding. The active step animates into a short pill
 * in Telepix blue; completed steps stay dimmed. Kept visually quiet so content dominates.
 */
@Composable
fun OnboardingProgress(
    stepIndex: Int,
    totalSteps: Int,
    progressLabel: String,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val completedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier.semantics { contentDescription = progressLabel },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            val isActive = index == stepIndex
            val isCompleted = index < stepIndex
            val width by animateDpAsState(
                targetValue = if (isActive) 22.dp else 8.dp,
                animationSpec = tween(durationMillis = 250),
                label = "dotWidth",
            )
            val color by animateColorAsState(
                targetValue = when {
                    isActive -> activeColor
                    isCompleted -> completedColor
                    else -> inactiveColor
                },
                animationSpec = tween(durationMillis = 250),
                label = "dotColor",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
    }
}
