package com.telepic.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens
import kotlinx.coroutines.delay

/**
 * The app's single transient-feedback surface: an auto-dismissing message bar used by every screen
 * that must honestly report the outcome of a user action ("2 queued · 3 already backed up",
 * "delete failed"). Centralized so the previously duplicated hand-rolled delay+Surface blocks and
 * the lone SnackbarHost all render identically; the caller owns the message text and its meaning.
 *
 * [messageId] is the caller's identity token for the *event*, which is not always the text: two bulk
 * backups with the same counts render the same sentence, and keying the timer on the text alone let
 * the second one inherit the first one's nearly-expired countdown. Callers that can distinguish
 * their events (e.g. [BulkBackupReport.id]) should pass it; when null the text is the only key.
 *
 * [actionLabel] / [onAction] add one reversible action (the Viewer's "Moved to trash · Undo"). Both
 * must be supplied to show it. An action also lengthens the timer: the auto-dismiss window is tuned
 * for *reading* a sentence, which is not long enough to read it, decide, and hit a button.
 */
@Composable
fun TransientMessageBar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    messageId: Any? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val action = if (actionLabel != null && onAction != null) onAction else null
    // The platform's own recommendation, not a hard-coded guess: Android's "Time to take action"
    // setting asks for a longer window, and 2.5s can otherwise expire mid-announcement for a
    // screen-reader user. Null (no AndroidComposeView above us) falls back to the fixed default.
    // An action needs a longer base than a read-only message: the window is otherwise sized for
    // reading a sentence, not for reading it, deciding, and aiming at a button.
    val baseDismissMs = if (action != null) ACTION_DISMISS_MS else AUTO_DISMISS_MS
    val autoDismissMs = LocalAccessibilityManager.current
        ?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = baseDismissMs,
            containsIcons = true,
            containsText = true,
            containsControls = true, // the action and dismiss controls below
        )
        ?: baseDismissMs

    LaunchedEffect(message, messageId) {
        delay(autoDismissMs)
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
        Row(
            modifier = Modifier.padding(
                start = TelepicTokens.spacing.md,
                end = TelepicTokens.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .weight(1f)
                    // The live region sits on the message text itself, not on the Surface: Surface
                    // does not merge its descendants, so a live region there would own no text to
                    // announce. On the Text the outcome is spoken as it appears, instead of a bar
                    // that vanishes in seconds being missed entirely. Polite (not Assertive) so it
                    // waits for the user's current speech rather than interrupting it.
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(vertical = TelepicTokens.spacing.sm),
            )
            // The reversible action sits before the dismiss button, so the primary affordance is the
            // first thing reached in reading order (and by a screen reader's swipe order).
            if (action != null && actionLabel != null) {
                TextButton(onClick = action) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
            // The bar covers content and auto-hides, so it needs its own way out — otherwise a
            // user who has read it (or wants it gone now) has to wait the timer out.
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_dismiss),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

private const val AUTO_DISMISS_MS = 2_500L

/**
 * Longer window when the bar carries an action. Long enough to read the message, decide, and reach
 * the button; still short enough that the bar does not linger over the content it is reporting on.
 */
private const val ACTION_DISMISS_MS = 5_000L
