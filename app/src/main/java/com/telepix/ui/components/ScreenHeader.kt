package com.telepix.ui.components

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
import com.telepix.R
import com.telepix.ui.theme.TelepixTokens

/**
 * Consistent top header for a Telepix screen. Centralized so every destination shares the
 * same title placement, style and margins. When [onBack] is supplied (a pushed/detail screen) it
 * renders a leading back affordance so those screens are navigable in-app, not only via system Back.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    onBack: (() -> Unit)? = null,
) {
    val spacing = TelepixTokens.spacing
    val text = @Composable {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }

    if (onBack == null) {
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
                .padding(start = spacing.xs, end = spacing.screenMargin, top = spacing.sm, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.albums_back),
                )
            }
            text()
        }
    }
}
