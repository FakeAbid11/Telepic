package com.telepic.ui.screens.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.PhotosItem
import com.telepic.ui.theme.TelepicTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private const val RAIL_MAX_ENTRIES = 12

/** A loaded day header, its grid index, and its epoch day (for ordering / rail emphasis). */
data class DateAnchor(val index: Int, val epochDay: Long, val absoluteLabel: String)

/**
 * Rebuild or extend the day-anchor list for a pager whose loaded count changed to [newCount].
 *
 * When [previous] is a valid prefix of the current contents — every anchor still sits at its
 * recorded index and [firstUncheckedIndex] lies within [newCount] — only the never-examined tail
 * `[firstUncheckedIndex, newCount)` is scanned and appended, so paging costs O(new items) instead
 * of O(loaded list). Anything else (a refresh remapping indices, a shrinking list) rebuilds by
 * scanning [newCount] items from scratch. [dayAt] returns the day item at an index, or null for
 * media/placeholder/absent cells.
 */
internal fun updateDateAnchors(
    previous: List<DateAnchor>,
    firstUncheckedIndex: Int,
    newCount: Int,
    dayAt: (Int) -> PhotosItem.Day?,
): List<DateAnchor> {
    fun scanFrom(start: Int): List<DateAnchor> {
        val result = ArrayList<DateAnchor>()
        for (index in start until newCount) {
            val day = dayAt(index) ?: continue
            result += DateAnchor(index, day.epochDay, day.label)
        }
        return result
    }

    val prefixValid = previous.isNotEmpty() &&
        firstUncheckedIndex in 0..newCount &&
        previous.all { anchor -> anchor.index < firstUncheckedIndex && dayAt(anchor.index)?.epochDay == anchor.epochDay }
    if (prefixValid) {
        return previous + scanFrom(firstUncheckedIndex)
    }
    return scanFrom(0)
}

/** Composable-side anchor state: recomputes only when the loaded count actually changes. */
internal class AnchorScanHolder {
    private var examinedCount: Int = -1
    var anchors: List<DateAnchor> = emptyList()
        private set

    fun update(newCount: Int, dayAt: (Int) -> PhotosItem.Day?): List<DateAnchor> {
        if (newCount == examinedCount) return anchors
        // Growth appends from the previously examined boundary; anything else scans from zero.
        val firstUnchecked = if (newCount > examinedCount && examinedCount >= 0) examinedCount else 0
        examinedCount = newCount
        anchors = updateDateAnchors(anchors, firstUnchecked, newCount, dayAt)
        return anchors
    }
}

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
    selectedIds: Set<Long> = emptySet(),
    selectionActive: Boolean = false,
    favoriteIds: Set<Long> = emptySet(),
    onToggleSelect: (LocalMedia) -> Unit = {},
    onLongSelect: (LocalMedia) -> Unit = {},
) {
    val spacing = TelepicTokens.spacing
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    // Rail anchors grow incrementally as pages append: only items past the last examined index are
    // scanned per change, instead of re-walking the whole loaded list. A refresh (new PagingData
    // generation) remaps every index, so it rebuilds from scratch.
    val anchorHolder = remember(media) { AnchorScanHolder() }
    val anchors: List<DateAnchor> = anchorHolder.update(media.itemCount) { index ->
        media[index] as? PhotosItem.Day
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
            columns = GridCells.Adaptive(minSize = spacing.gridTileMinSize),
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
                        onClick = {
                            if (selectionActive) onToggleSelect(item.media) else onMediaSelected(item.media)
                        },
                        onLongClick = { onLongSelect(item.media) },
                        selected = item.media.id in selectedIds,
                        selectionActive = selectionActive,
                        isFavorite = item.media.id in favoriteIds,
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
    val spacing = TelepicTokens.spacing
    val date = LocalDate.ofEpochDay(epochDay)
    val diff = today.toEpochDay() - epochDay
    val locale = Locale.getDefault()
    // Formatters are locale-bound but pattern-constant: build once per locale instead of per
    // header recomposition (DateTimeFormatter.ofPattern is not cheap).
    val weekdayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE", locale) }
    val monthDayFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMMM d", locale) }
    val label = when {
        diff == 0L -> stringResource(R.string.date_today)
        diff == 1L -> stringResource(R.string.date_yesterday)
        diff in 2..6 -> date.format(weekdayFormatter)
        date.year == today.year -> date.format(monthDayFormatter)
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateRail(
    anchors: List<DateAnchor>,
    today: LocalDate,
    activeEpochDay: Long?,
    onAnchorClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (anchors.size < 2) return
    val spacing = TelepicTokens.spacing
    val railDescription = stringResource(R.string.photos_date_rail)
    val selectedLabel = stringResource(R.string.date_rail_selected)
    val notSelectedLabel = stringResource(R.string.date_rail_not_selected)
    val locale = Locale.getDefault()
    val shortFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }

    // Newest-first; show a bounded window that defaults to the recent days and slides to keep the
    // currently-active day visible as the user scrolls into older dates.
    val activePos = anchors.indexOfFirst { it.epochDay == activeEpochDay }.coerceAtLeast(0)
    val window = remember(anchors.size, activePos) {
        DateRailWindow.window(anchors.size, activePos, RAIL_MAX_ENTRIES)
    }
    if (window.isEmpty()) return

    Column(
        modifier = modifier
            .width(spacing.railWidth)
            .padding(top = spacing.sm, bottom = spacing.sm)
            .semantics { contentDescription = railDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        anchors.subList(window.first, window.last + 1).forEach { anchor ->
            val isActive = anchor.epochDay == activeEpochDay
            val short = when (today.toEpochDay() - anchor.epochDay) {
                0L -> stringResource(R.string.date_today)
                1L -> stringResource(R.string.date_yesterday)
                else -> LocalDate.ofEpochDay(anchor.epochDay).format(shortFormatter)
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp, max = 48.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(
                        onClick = { onAnchorClick(anchor.index) },
                        onLongClick = { onAnchorClick(anchor.index) },
                    )
                    .semantics {
                        contentDescription = "$short, ${if (isActive) selectedLabel else notSelectedLabel}"
                        if (isActive) { this.selected = true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Active is signalled by more than color: a leading bar plus bold weight.
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .padding(end = spacing.xs)
                                .size(width = 3.dp, height = 16.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                    }
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
}

@Composable
private fun LoadingFooter(modifier: Modifier = Modifier) {
    val spacing = TelepicTokens.spacing
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
    val spacing = TelepicTokens.spacing
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
