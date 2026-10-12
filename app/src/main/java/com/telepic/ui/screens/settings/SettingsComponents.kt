package com.telepic.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
 *
 * A row with no [onClick] is plain information: it is neither clickable nor disabled, and announces
 * no state at all. Only a row that *has* an action can be switched off.
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
    // The clickable modifier is attached only when the row actually has an action. Attaching it
    // unconditionally meant an informational row ran through `clickable(enabled = false)`, which
    // publishes the Disabled accessibility state — so a row sitting at full contrast announced
    // itself as "disabled". `enabled` now only ever qualifies a real action.
    val action = onClick
    val rowModifier = if (action == null) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = action)
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
        modifier = rowModifier,
    )
}

/**
 * A settings row that switches something on or off.
 *
 * The whole row is the control (`toggleable` with [Role.Switch]) rather than only the switch itself,
 * so the tap target is the full 56dp list item instead of a 32dp thumb — and a screen reader
 * announces one control with its label, not a bare switch next to a stray heading. The [Switch]
 * therefore takes `onCheckedChange = null`: it renders the state, and the row owns the interaction.
 */
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
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
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            headlineColor = contentColor,
            supportingColor = supportingColor,
        ),
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}
