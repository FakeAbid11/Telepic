package com.telepix.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.telepix.ui.theme.TelepixTokens

/**
 * Consistent top header for a Telepix screen. Centralized so every destination shares the
 * same title placement, style and margins.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    val spacing = TelepixTokens.spacing
    Text(
        text = title,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenMargin, vertical = spacing.lg)
            .semantics { heading() },
    )
}
