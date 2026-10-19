package com.telepic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * from screen readers, and the icon, the title and the description are merged into one
 * announcement.
 *
 * The actions are **not** part of that merge: a merged semantics node carries a single
 * `onClick`, so merging two buttons made the second one swallow the first. Each action is
 * its own focusable node, which is also what lets a screen-reader user choose between them.
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
                // A long body must never put the actions out of reach. The Cloud consent card
                // carries the app's longest empty-state copy (the ChatValidator reason) and clears a
                // 360x640dp phone by only ~10dp, so on a smaller screen or at a larger font scale
                // its second choice was laid out below the window and could not be tapped at all.
                // When the content fits, `verticalScroll` measures to the content and the Box still
                // centres it, so short empty states render exactly as before.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterVertically),
        ) {
            // The illustration, the title and the description are one announcement: the icon is
            // decorative and the title already carries the meaning.
            //
            // The **actions are deliberately outside this merge**. A merged semantics node keeps a
            // single `onClick` — the last child's — so merging the two buttons made the secondary
            // action swallow the primary one. On the Cloud consent card that meant a screen-reader
            // user double-tapping the card ran "create a new channel" instead of "use this one",
            // which is the one outcome the screen's own comment says Telepic must never choose for
            // the user.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
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
            }
            if (primaryAction != null || secondaryAction != null) {
                Column(
                    modifier = Modifier.padding(top = spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    primaryAction?.let { action ->
                        Button(onClick = action.onClick, enabled = action.enabled) {
                            Text(action.label)
                        }
                    }
                    secondaryAction?.let { action ->
                        TextButton(onClick = action.onClick, enabled = action.enabled) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}
