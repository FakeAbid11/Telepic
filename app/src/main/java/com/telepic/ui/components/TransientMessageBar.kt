package com.telepic.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.telepic.ui.theme.TelepicTokens
import kotlinx.coroutines.delay

/**
 * The app's single transient-feedback surface: an auto-dismissing message bar used by every screen
 * that must honestly report the outcome of a user action ("2 queued · 3 already backed up",
 * "delete failed"). Centralized so the previously duplicated hand-rolled delay+Surface blocks and
 * the lone SnackbarHost all render identically; the caller owns the message text and its meaning.
 */
@Composable
fun TransientMessageBar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message) {
        delay(AUTO_DISMISS_MS)
        onDismiss()
    }
    Surface(
        modifier = modifier.padding(
            horizontal = TelepicTokens.spacing.screenMargin,
            vertical = TelepicTokens.spacing.lg,
        ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(
                horizontal = TelepicTokens.spacing.md,
                vertical = TelepicTokens.spacing.sm,
            ),
        )
    }
}

private const val AUTO_DISMISS_MS = 2_500L
