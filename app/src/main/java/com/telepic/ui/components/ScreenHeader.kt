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
import androidx.compose.ui.text.style.TextOverflow
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens

/**
 * Consistent top header for a Telepic screen, and the **single owner** of the screen-title contract:
 * the `headlineMedium` size, the margins, the one-line clamp and — critically — the
 * [androidx.compose.ui.semantics.heading] semantics that let a screen reader jump straight to a
 * screen's title instead of reading the grid beneath it.
 *
 * That ownership is the point. Three destinations (Photos, album contents, Favorites/Archive/Trash)
 * used to hand-roll this row because they needed a trailing *row* of controls rather than one
 * control, and because the component could not clamp a long title. Each hand-rolled copy restated the
 * size and the padding but silently dropped the heading semantics — so the app's own landing screen
 * was the one place TalkBack could not find a heading. [trailing] now takes any composable (a `Row`
 * of controls included) and [titleMaxLines] carries the clamp, so there is no reason left to
 * re-implement this.
 *
 * When [onBack] is supplied (a pushed/detail screen) a leading back affordance is rendered, so those
 * screens are navigable in-app and not only via system Back. [trailing] sits at the header's end.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = 1,
) {
    val spacing = TelepicTokens.spacing
    val text = @Composable {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            // Clamped by default: a header is a one-line band, and an unbounded title (a long album
            // name, a long date) grew the row and pushed the content down. Ellipsis degrades instead
            // of reflowing.
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }

    if (onBack == null && trailing == null) {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
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
                        contentDescription = stringResource(R.string.nav_back),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) { text() }
            trailing?.invoke()
        }
    }
}
