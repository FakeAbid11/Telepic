package com.telepic.ui.screens.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the scrubber words its proportional labels. */
internal enum class SectionGranularity { Month, Year }

/** Most section labels drawn at once — bounds the overlay however deep the library is. */
internal const val SCRUB_SECTION_WINDOW = 12

/**
 * The first anchor of each calendar month (newest-first): a "Sep 2022"-style label position.
 * The first anchor is always kept so the newest month is reachable.
 */
internal fun monthSections(anchors: List<DateAnchor>): List<DateAnchor> =
    anchors.filterIndexed { i, anchor ->
        if (i == 0) return@filterIndexed true
        val current = LocalDate.ofEpochDay(anchor.epochDay)
        val previous = LocalDate.ofEpochDay(anchors[i - 1].epochDay)
        current.year != previous.year || current.month != previous.month
    }

/**
 * The first anchor of each calendar year (newest-first): a "2022"-style label position. Only
 * meaningful for libraries deep enough that month labels would crowd the track.
 */
internal fun yearSections(anchors: List<DateAnchor>): List<DateAnchor> =
    anchors.filterIndexed { i, anchor ->
        if (i == 0) return@filterIndexed true
        LocalDate.ofEpochDay(anchor.epochDay).year !=
            LocalDate.ofEpochDay(anchors[i - 1].epochDay).year
    }

/**
 * Pick the label set for the loaded anchors: month boundaries, or year boundaries when the
 * library is too deep for month labels to fit in one window. Null when there is nothing to label.
 */
internal fun chooseSections(
    anchors: List<DateAnchor>,
    maxSections: Int,
): Pair<List<DateAnchor>, SectionGranularity>? {
    if (anchors.isEmpty()) return null
    val months = monthSections(anchors)
    if (months.size <= maxSections) return months to SectionGranularity.Month
    val years = yearSections(anchors)
    return if (years.isEmpty()) null else years to SectionGranularity.Year
}

/** Map a 0..1 track fraction (newest at the top) to a grid item index to scrub to. */
internal fun scrubIndex(fraction: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
}

/**
 * Google-Photos-style date scrubber: invisible at rest, fading in a slim proportional thumb and
 * section labels while scrolling, and — while the thumb is dragged — a large date bubble that
 * tracks the finger as the grid scrubs through the already-loaded pages.
 */
@Composable
internal fun DateScrubber(
    anchors: List<DateAnchor>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    if (anchors.size < 2) return
    val spacing = TelepicTokens.spacing
    val scope = rememberCoroutineScope()
    val locale = Locale.getDefault()
    // Locale-bound but pattern-constant formatters, built once per locale.
    val sectionMonth = remember(locale) { DateTimeFormatter.ofPattern("MMM yyyy", locale) }
    val sectionYear = remember(locale) { DateTimeFormatter.ofPattern("yyyy", locale) }
    val bubbleMonthDay = remember(locale) { DateTimeFormatter.ofPattern("MMMM d", locale) }
    val bubbleMonthYear = remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }
    val railDescription = stringResource(R.string.photos_date_rail)
    val today = LocalDate.now()

    val (sections, granularity) = remember(anchors) { chooseSections(anchors, SCRUB_SECTION_WINDOW) }
        ?: return

    // ---- Fade lifecycle: visible while scrolling or dragging, gone shortly after idle. ----
    var resting by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) resting = true else {
            delay(1_200)
            resting = false
        }
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (resting) 1f else 0f,
        label = "scrubberAlpha",
    )

    // ---- Thumb geometry: proportional to the loaded content, driven by the first visible item. ----
    var trackHeightPx by remember { mutableStateOf(1f) }
    val thumb by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo
            if (total <= 0 || visible.isEmpty()) return@derivedStateOf 0f to 0.1f
            val heightFraction = (visible.size.toFloat() / total).coerceIn(0.06f, 1f)
            val topIndex = visible.first().index
            val topFraction = (topIndex.toFloat() / total).coerceIn(0f, 1f - heightFraction)
            topFraction to heightFraction
        }
    }

    // ---- Scrub state: the finger's track fraction while dragging, else the scroll-derived one. ----
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }
    val thumbTop = scrubFraction ?: thumb.first
    val scrubItemIndex = scrubIndex(thumbTop, gridState.layoutInfo.totalItemsCount)
    val scrubAnchor = anchors.lastOrNull { it.index <= scrubItemIndex } ?: anchors.first()

    Box(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .semantics { contentDescription = railDescription },
    ) {
        // ---- Section labels, positioned proportionally along the track (fade with the overlay). ----
        val itemCount = gridState.layoutInfo.totalItemsCount
        val window by remember(sections) {
            derivedStateOf {
                DateRailWindow.window(
                    sections.size,
                    activeSection(sections, gridState),
                    SCRUB_SECTION_WINDOW,
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().alpha(overlayAlpha)) {
            sections.subList(window.first, window.last + 1).forEach { section ->
                val date = LocalDate.ofEpochDay(section.epochDay)
                Text(
                    text = when (granularity) {
                        SectionGranularity.Month -> date.format(sectionMonth)
                        SectionGranularity.Year -> date.format(sectionYear)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 14.dp)
                        .offset {
                            IntOffset(
                                0,
                                ((section.index.toFloat() / itemCount.coerceAtLeast(1)) * trackHeightPx)
                                    .roundToInt(),
                            )
                        },
                )
            }
        }

        // ---- The thumb: a slim pill that doubles as the drag handle. ----
        val density = LocalDensity.current
        val minThumbPx = with(density) { 32.dp.toPx() }
        val thumbHeightPx = (thumb.second * trackHeightPx).coerceAtLeast(minThumbPx)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(28.dp)
                .fillMaxHeight()
                .draggable(
                    state = rememberDraggableState { delta ->
                        val start = scrubFraction ?: thumb.first
                        scrubFraction = (start + delta / trackHeightPx).coerceIn(0f, 1f)
                        scrubJob?.cancel()
                        scrubJob = scope.launch {
                            gridState.scrollToItem(
                                scrubIndex(scrubFraction ?: 0f, gridState.layoutInfo.totalItemsCount),
                            )
                        }
                    },
                    orientation = Orientation.Vertical,
                    onDragStarted = {
                        dragging = true
                        scrubFraction = thumb.first
                    },
                    onDragStopped = {
                        scrubFraction = null
                        dragging = false
                    },
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = spacing.xs)
                    .offset { IntOffset(0, (thumbTop * (trackHeightPx - thumbHeightPx)).roundToInt()) }
                    .width(5.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .alpha(overlayAlpha)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }

        // ---- The date bubble: only while dragging, beside the thumb. ----
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val bubbleDate = LocalDate.ofEpochDay(scrubAnchor.epochDay)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.padding(end = 24.dp),
            ) {
                Text(
                    text = if (bubbleDate.year == today.year) {
                        bubbleDate.format(bubbleMonthDay)
                    } else {
                        bubbleDate.format(bubbleMonthYear)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                )
            }
        }
    }
}

/** The section covering the grid's first visible item (newest-first anchors). */
private fun activeSection(sections: List<DateAnchor>, gridState: LazyGridState): Int {
    val firstVisible = gridState.firstVisibleItemIndex
    return sections.indices.lastOrNull { sections[it].index <= firstVisible } ?: 0
}
