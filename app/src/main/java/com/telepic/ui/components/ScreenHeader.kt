package com.telepic.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens

/**
 * Consistent top header for a Telepic screen. Centralized so every destination shares the
 * same title placement, style and margins. When [onBack] is supplied (a pushed/detail screen) it
 * renders a leading back affordance so those screens are navigable in-app, not only via system Back.
 * An optional [trailing] composable (e.g. a live count) sits at the header's end.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = TelepicTokens.spacing
    val text = @Composable {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }

    if (onBack == null && trailing == null) {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenMargin, vertical = spacing.lg)
                .semantics { heading() },
        )
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    start = if (onBack != null) spacing.xs else spacing.screenMargin,
                    end = if (trailing != null) spacing.sm else spacing.screenMargin,
                    top = if (onBack != null) spacing.sm else spacing.lg,
                    bottom = if (onBack != null) spacing.sm else spacing.lg,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.albums_back),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) { text() }
            trailing?.invoke()
        }
    }
}
