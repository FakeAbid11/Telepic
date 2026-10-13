package com.telepic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telepic.ui.theme.TelepicTokens

/**
 * The first-load placeholder for a media grid.
 *
 * A skeleton rather than a spinner because the shape is the message: it says "a grid of photos is
 * coming", and it reserves the space those photos will occupy so the content does not jump when it
 * lands. Photos had one and every other grid did not, which made the same wait look like two
 * different apps.
 *
 * The column count is derived from the available width exactly as `GridCells.Adaptive` does, so the
 * placeholder has the same density as the grid it stands in for — a fixed three-column skeleton over
 * a nine-column tablet grid would read as a different screen.
 *
 * [minCellSize] must match the grid this precedes.
 */
@Composable
fun MediaGridSkeleton(
    minCellSize: Dp,
    modifier: Modifier = Modifier,
    rows: Int = 4,
) {
    val spacing = TelepicTokens.spacing
    BoxWithConstraints(modifier = modifier.fillMaxSize().padding(spacing.gridGutter)) {
        // Same arithmetic as GridCells.Adaptive: how many (cell + gutter) fit in (width + gutter).
        val columns = ((maxWidth + spacing.gridGutter) / (minCellSize + spacing.gridGutter))
            .toInt()
            .coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.gridGutter)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter)) {
                    repeat(columns) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(spacing.gridItemRadius))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The first-load placeholder for a vertical list of rows (Backup Center's queue). Same reasoning as
 * [MediaGridSkeleton] — reserve the shape, not just the space.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 5,
    rowHeight: Dp = 56.dp,
) {
    val spacing = TelepicTokens.spacing
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        repeat(rows) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(spacing.gridItemRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
