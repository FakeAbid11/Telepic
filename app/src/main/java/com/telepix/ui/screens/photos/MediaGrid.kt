package com.telepix.ui.screens.photos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.telepix.R
import com.telepix.domain.backup.MediaBackupVisualState
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.PhotosItem
import com.telepix.ui.theme.TelepixTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private const val RAIL_MAX_ENTRIES = 12

/** A loaded day header, its grid index, and its epoch day (for ordering / rail emphasis). */
data class DateAnchor(val index: Int, val epochDay: Long, val absoluteLabel: String)

/**
 * The Photos media grid (Phase 8): adaptive, thumbnail-first, lazy, with localized relative day
 * headers (Today / Yesterday / weekday / date), a right-side date rail that jumps among the loaded
 * pages and highlights the active day, per-tile backup badges read from a repository-provided
 * snapshot map, and a paging footer for append loading / inline retry. It runs no Room query, no
 * hashing and no network call per tile — status comes from [backupStates].
 */
@Composable
fun MediaGrid(
    media: LazyPagingItems<PhotosItem>,
    backupStates: Map<Long, MediaBackupVisualState>,
    onMediaSelected: (LocalMedia) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val spacing = TelepixTokens.spacing
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    // Rail anchors rebuild only when the loaded count changes — not per recomposition. Each anchor
    // is a *loaded* day header, so the rail grows with paging and never force-loads the library.
    val anchors: List<DateAnchor> = remember(media.itemCount) {
        val result = ArrayList<DateAnchor>()
        for (index in 0 until media.itemCount) {
            val item = media[index]
            if (item is PhotosItem.Day) result += DateAnchor(index, item.epochDay, item.label)
        }
        result
    }

    // The active rail entry is the last anchor at/above the first visible item — derived so it
    // tracks scrolling without recomputing the anchors.
    val activeIndex by remember(anchors) {
        derivedStateOf {
            val firstVisible = gridState.firstVisibleItemIndex
            anchors.indices.lastOrNull { anchors[it].index <= firstVisible } ?: 0
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = spacing.gridGutter,
                end = spacing.railGutter,
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
                    if (media[index] is PhotosItem.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
                contentType = { index -> if (media[index] is PhotosItem.Day) "day" else "media" },
            ) { index ->
                when (val item = media[index]) {
                    is PhotosItem.Day -> DayHeader(epochDay = item.epochDay, absoluteLabel = item.label, today = today)
                    is PhotosItem.Media -> MediaTile(
                        media = item.media,
                        backupState = backupStates[item.media.id] ?: MediaBackupVisualState.NONE,
                        onClick = { onMediaSelected(item.media) },
                    )
                    null -> Box(modifier = Modifier.aspectRatio(1f))
                }
            }

            // Paging footer: a small end-of-list spinner, or an inline retry on append failure —
            // already-loaded photos are never replaced by a full-screen spinner.
            when (media.loadState.append) {
                is LoadState.Loading -> item(key = "loading_footer", span = { GridItemSpan(maxLineSpan) }) {
                    LoadingFooter()
                }
                is LoadState.Error -> item(key = "error_footer", span = { GridItemSpan(maxLineSpan) }) {
                    AppendErrorFooter(onRetry = { media.retry() })
                }
                else -> Unit
            }
        }

        DateRail(
            anchors = anchors,
            today = today,
            activeEpochDay = anchors.getOrNull(activeIndex)?.epochDay,
            onAnchorClick = { index -> scope.launch { gridState.animateScrollToItem(index) } },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** A localized relative day header: Today / Yesterday / weekday / month-day / full date. */
@Composable
fun DayHeader(epochDay: Long, absoluteLabel: String, today: LocalDate, modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    val date = LocalDate.ofEpochDay(epochDay)
    val diff = today.toEpochDay() - epochDay
    val locale = Locale.getDefault()
    val label = when {
        diff == 0L -> stringResource(R.string.date_today)
        diff == 1L -> stringResource(R.string.date_yesterday)
        diff in 2..6 -> date.format(DateTimeFormatter.ofPattern("EEEE", locale))
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("MMMM d", locale))
        else -> absoluteLabel
    }
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
 * Right-side date rail: compact localized day labels for the loaded pages, with the active day
 * emphasized. Tapping an entry scrolls the grid to that already-loaded day. Kept narrow and
 * unobtrusive; its column meets the Material touch-target width.
 */
@Composable
private fun DateRail(
    anchors: List<DateAnchor>,
    today: LocalDate,
    activeEpochDay: Long?,
    onAnchorClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (anchors.size < 2) return
    val spacing = TelepixTokens.spacing
    val railDescription = stringResource(R.string.photos_date_rail)
    val locale = Locale.getDefault()
    val shortFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }

    Column(
        modifier = modifier
            .width(spacing.railWidth)
            .padding(top = spacing.lg, bottom = spacing.lg)
            .semantics { contentDescription = railDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        anchors.takeLast(RAIL_MAX_ENTRIES).forEach { anchor ->
            val isActive = anchor.epochDay == activeEpochDay
            val short = when (today.toEpochDay() - anchor.epochDay) {
                0L -> stringResource(R.string.date_today)
                1L -> stringResource(R.string.date_yesterday)
                else -> LocalDate.ofEpochDay(anchor.epochDay).format(shortFormatter)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onAnchorClick(anchor.index) }
                    .padding(horizontal = spacing.xs, vertical = 3.dp),
            ) {
                Text(
                    text = short,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingFooter(modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.photos_loading_more),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.sm),
        )
    }
}

@Composable
private fun AppendErrorFooter(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TelepixTokens.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm, horizontal = spacing.screenMargin),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.photos_load_more_error),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.photos_retry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        )
    }
}
