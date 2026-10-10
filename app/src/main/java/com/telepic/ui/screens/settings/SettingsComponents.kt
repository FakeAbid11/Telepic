package com.telepic.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.telepic.ui.theme.TelepicTokens

/** A titled group of settings rows. */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = TelepicTokens.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = spacing.screenMargin,
                end = spacing.screenMargin,
                top = spacing.lg,
                bottom = spacing.sm,
            ),
        )
        content()
    }
}

/**
 * A single settings row. Disabled rows are dimmed, non-clickable, and announce themselves as
 * disabled to accessibility services — used for features that arrive in later phases so a row
 * never looks actionable but does nothing. The caller's [summary] should state the honest status
 * (e.g. "coming soon"), not fake an outcome.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clickable = enabled && onClick != null
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val supportingColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    ListItem(
        headlineContent = { Text(title, color = contentColor) },
        supportingContent = summary?.let { { Text(it, color = supportingColor) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            headlineColor = contentColor,
            supportingColor = supportingColor,
        ),
        modifier = modifier
            .fillMaxWidth()
            // clickable(enabled = false) already publishes the Disabled accessibility state.
            .clickable(enabled = clickable, onClick = { onClick?.invoke() }),
    )
}
