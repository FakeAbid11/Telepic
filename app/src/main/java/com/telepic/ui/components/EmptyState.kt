package com.telepic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.telepic.ui.theme.TelepicTokens

/**
 * Reusable empty state used across Photos, Cloud, Albums and Map so every screen
 * communicates "nothing here yet" the same intentional way.
 *
 * The [icon] is decorative — the [title] already carries the meaning — so it is hidden
 * from screen readers and the whole block merges its descendants for a clean announcement.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    primaryAction: StateAction? = null,
    secondaryAction: StateAction? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepicTokens.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(PaddingValues(spacing.xl)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = spacing.md)
                    .semantics { heading() },
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = spacing.md),
                )
            }
            if (primaryAction != null || secondaryAction != null) {
                Column(
                    modifier = Modifier.padding(top = spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    primaryAction?.let { action ->
                        Button(onClick = action.onClick) {
                            Text(action.label)
                        }
                    }
                    secondaryAction?.let { action ->
                        TextButton(onClick = action.onClick) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}
