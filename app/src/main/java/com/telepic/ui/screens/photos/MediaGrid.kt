package com.telepic.ui.screens.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.PhotosItem
import com.telepic.domain.media.localizedDateFormatter
import com.telepic.ui.theme.TelepicTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** Composable-side anchor state: recomputes when the loaded count OR the paging generation changes. */
internal class AnchorScanHolder {
    private var examinedCount: Int = -1
    private var examinedFirstKey: String? = null
    var anchors: List<DateAnchor> = emptyList()
        private set

    /**
     * [firstKey] identifies the loaded generation (the head cell's stable key). A refresh can land on
     * exactly the same item count, which the count alone cannot distinguish — without the key check
     * the rail would keep pointing at the previous generation's rows.
     */
    fun update(newCount: Int, firstKey: String?, dayAt: (Int) -> PhotosItem.Day?): List<DateAnchor> {
        val sameGeneration = newCount == examinedCount && firstKey == examinedFirstKey
        if (sameGeneration) return anchors
        // Append only within one generation that grew; any other change rescans from the start.
        val appendedWithinGeneration = newCount > examinedCount && examinedCount >= 0 && firstKey == examinedFirstKey
        val firstUnchecked = if (appendedWithinGeneration) examinedCount else 0
        examinedCount = newCount
        examinedFirstKey = firstKey
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
    // Read through `rememberUpdatedState` so the per-cell lambdas below can be `remember`ed without
    // the callbacks among their keys. A bound method reference (`viewModel::toggleSelection`) is a
    // *new instance* on every recomposition of the caller, so keying on it would recreate the lambda
    // every time and defeat the whole point of hoisting it. These wrappers keep a stable instance
    // that still reads the latest callback when it is invoked.
    val currentOnSelected by rememberUpdatedState(onMediaSelected)
    val currentOnToggle by rememberUpdatedState(onToggleSelect)
    val currentOnLongSelect by rememberUpdatedState(onLongSelect)
    // Read fresh on every (re)composition: a process-lifetime remember kept labelling "Today"
    // for a session that crossed midnight. LocalDate.now() is cheap (no I/O), so staleness
    // only lasts until the next recomposition instead of forever.
    val today = LocalDate.now()
    // Announced so long-press-to-select is reachable by a screen reader, which cannot discover a
    // bare gesture. Resolved here rather than threaded through the callers: it is the same words on
    // every grid, and a caller that had to remember to pass it is a caller that could forget.
    val longPressLabel = stringResource(R.string.media_long_press_select)

    // Scrubber anchors grow incrementally as pages append: only items past the last examined index
    // are scanned per change, instead of re-walking the whole loaded list. The head cell's key carries
    // the Paging generation, so a refresh — even one landing on the same item count — rebuilds from zero.
    //
    // The scan runs inside `remember`, not directly in the composition body: `update` mutates the
    // holder, and a mutation in composition re-runs on every recomposition of this grid — which
    // happens on each backup-progress tick. Keying on the loaded count and the head cell's key means
    // it runs exactly when the paging generation actually changes, which is the same condition the
    // holder itself already used to decide whether to reuse its cached anchors.
    val anchorHolder = remember { AnchorScanHolder() }
    val loadedCount = media.itemCount
    val firstKey = media[0]?.key
    val anchors: List<DateAnchor> = remember(anchorHolder, loadedCount, firstKey) {
        anchorHolder.update(loadedCount, firstKey) { index -> media[index] as? PhotosItem.Day }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = spacing.gridTileMinSize),
            state = gridState,
            contentPadding = PaddingValues(
                start = spacing.gridGutter,
                end = spacing.gridGutter,
                top = spacing.xs,
                // Room for the selection action bar, which overlays the bottom of the grid while a
                // selection is active. Without it the bar covered the last row of tiles and they
                // could never be scrolled clear of it.
                bottom = if (selectionActive) {
                    spacing.xl + spacing.selectionActionBarHeight
                } else {
                    spacing.xl
                },
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
                    is PhotosItem.Media -> {
                        val cell = item.media
                        // Hoisted into `remember` so each cell gets a *stable* lambda instance. Written
                        // inline these capture the item, and a lambda that captures an unstable value
                        // cannot be memoised — so `MediaTile` saw a new `onClick` on every parent
                        // recomposition and could never skip, which is how a single backup-progress tick
                        // ended up recomposing every visible tile.
                        val onClick = remember(cell, selectionActive) {
                            { if (selectionActive) currentOnToggle(cell) else currentOnSelected(cell) }
                        }
                        val onLongClick = remember(cell) { { currentOnLongSelect(cell) } }
                        MediaTile(
                            media = cell,
                            backupState = backupStates[cell.id] ?: MediaBackupVisualState.NONE,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = longPressLabel,
                            selected = cell.id in selectedIds,
                            selectionActive = selectionActive,
                            isFavorite = cell.id in favoriteIds,
                        )
                    }
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

        // Which day the reader is inside right now. `LazyVerticalGrid` has no `stickyHeader`, so the
        // inline header row simply scrolls away and the timeline stops saying what month you are
        // looking at. Pinned only once that header's own row has left the viewport, so a day is never
        // shown twice at the same time.
        val firstVisibleRow by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
        val pinnedDay = anchors.lastOrNull { it.index <= firstVisibleRow }
            ?.takeIf { it.index < firstVisibleRow }
        pinnedDay?.let { anchor ->
            DayHeader(
                epochDay = anchor.epochDay,
                absoluteLabel = anchor.absoluteLabel,
                today = today,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(TelepicTokens.colors.mediaBackdrop)
                    // A visual aid only: the list's own header rows already announce the day as a
                    // heading, so this overlay would read the same date out twice.
                    .clearAndSetSemantics { },
            )
        }

        DateScrubber(
            anchors = anchors,
            gridState = gridState,
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
    val monthDayFormatter = remember(locale) { localizedDateFormatter(locale, "MMMMd", "MMMM d") }
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
        // A real button rather than a Text with `clickable`: the old target was roughly 28dp tall
        // and carried no button role, so it was hard to hit and announced as inert text. TextButton
        // keeps the primary-coloured label while giving the 48dp minimum touch target.
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.photos_retry),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
