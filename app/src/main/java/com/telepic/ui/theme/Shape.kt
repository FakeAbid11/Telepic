package com.telepic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Telepic shape scale.
 *
 * Favors moderate, clean rounding on cards and surfaces. Deliberately avoids turning
 * every element into a pill so the visual language stays crisp and media-focused.
 */
val TelepicShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
