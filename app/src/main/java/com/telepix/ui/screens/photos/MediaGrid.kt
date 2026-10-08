package com.telepix.ui.screens.photos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.telepix.R
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.PhotosItem
import com.telepix.ui.theme.TelepixTokens
import kotlinx.coroutines.launch

/** A date-rail entry: a visible day header and its grid index. */
data class DateAnchor(val index: Int, val label: String)

@Composable
fun DayHeader(label: String, modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = spacing.screenMargin,
                end = spacing.screenMargin,
                top = spacing.md,
                bottom = spacing.sm,
            )
            .semantics { heading() },
    )
}

/**
 * The Photos media grid: an adaptive, thumbnail-first, lazy grid with full-width day headers and
 * a subtle right-side date rail foundation. Stable keys come from the media/day identifiers.
 */
@Composable
fun MediaGrid(
    media: LazyPagingItems<PhotosItem>,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val spacing = TelepixTokens.spacing
    val scope = rememberCoroutineScope()

    // Recompute rail anchors only when the loaded count changes — not on every recomposition.
    val anchors: List<DateAnchor> = remember(media.itemCount) {
        val result = ArrayList<DateAnchor>()
        for (index in 0 until media.itemCount) {
            val item = media[index]
            if (item is PhotosItem.Day) result += DateAnchor(index, item.label)
        }
        result
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            cells = GridCells.Adaptive(minSize = 110.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = spacing.gridGutter,
                end = spacing.gridGutter,
                top = spacing.xs,
                bottom = spacing.xl,
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing.gridGutter),
            verticalArrangement = Arrangement.spacedBy(spacing.gridGutter),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = media.itemCount,
                key = { index -> media[index]?.key ?: "placeholder_$index" },
                span = { index ->
                    if (media[index] is PhotosItem.Day) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                },
            ) { index ->
                when (val item = media[index]) {
                    is PhotosItem.Day -> DayHeader(item.label)
                    is PhotosItem.Media -> MediaTile(
                        media = item.media,
                        onClick = { onMediaSelected(item.media) },
                    )
                    null -> Spacer(modifier = Modifier.aspectRatio(1f))
                }
            }
        }

        DateRail(
            anchors = anchors,
            onAnchorClick = { index -> scope.launch { gridState.scrollToItem(index) } },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * Right-side date rail foundation. Lists the day labels already loaded and scrolls the grid to
 * the selected day when tapped. Intentionally minimal and unobtrusive.
 */
@Composable
private fun DateRail(
    anchors: List<DateAnchor>,
    onAnchorClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (anchors.size < 2) return
    val spacing = TelepixTokens.spacing
    val railLabel = stringResource(R.string.photos_date_rail)
    Column(
        modifier = modifier
            .padding(top = spacing.lg, end = spacing.xs)
            .semantics { contentDescription = railLabel },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        anchors.take(RAIL_MAX_ENTRIES).forEach { anchor ->
            Text(
                text = anchor.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onAnchorClick(anchor.index) }
                    .padding(horizontal = spacing.xs, vertical = 2.dp),
            )
        }
    }
}

private const val RAIL_MAX_ENTRIES = 6
