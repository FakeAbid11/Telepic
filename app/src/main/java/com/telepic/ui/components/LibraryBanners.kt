package com.telepic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens

/**
 * The banner a screen shows when a *refresh* failed while it still has content on screen.
 *
 * Shared because the two screens that can hit it disagreed: the Photos timeline kept the loaded
 * photos and offered an inline retry, while an album replaced the whole grid with a full-screen
 * error — so pulling to refresh on one screen kept the user's photos and on the other threw them
 * away. The rule is the same on both: content that is already on screen is never replaced by an
 * error page; the error is reported *over* it, with the retry next to it.
 */
@Composable
internal fun RefreshErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TelepicTokens.spacing.md,
                vertical = TelepicTokens.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.photos_refresh_failed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.photos_retry))
            }
        }
    }
}

/**
 * The banner a screen shows when the user granted *partial* media access.
 *
 * Shared for the same reason as [RefreshErrorBanner]: the Photos timeline said so, and the Map and
 * Albums screens did not — so the same library looked complete on one tab and partial on another,
 * and the header's count was read as the whole library.
 */
@Composable
internal fun PartialAccessBanner(modifier: Modifier = Modifier) {
    val spacing = TelepicTokens.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.photos_partial_access_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
