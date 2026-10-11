package com.telepic.ui.screens.photos

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
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
 * Where a grid index sits along the track, as a 0..1 fraction. The exact inverse of [scrubIndex], so
 * a section label and the thumb can never disagree about where a given item lives — which is what
 * used to make the labels drift away from the pill as it grew.
 */
internal fun trackFraction(index: Int, itemCount: Int): Float {
    if (itemCount <= 1) return 0f
    return (index.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
}

/** Distance the thumb's top can travel, in px. Zero when the thumb fills the whole track. */
internal fun thumbTravelPx(trackHeightPx: Float, thumbHeightPx: Float): Float =
    (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)

/** Top offset of the thumb for a track fraction — the position the pill is drawn at. */
internal fun thumbTopPx(fraction: Float, trackHeightPx: Float, thumbHeightPx: Float): Float =
    fraction.coerceIn(0f, 1f) * thumbTravelPx(trackHeightPx, thumbHeightPx)

/**
 * The thumb's vertical centre for a track fraction. Labels and the date bubble sit here so they line
 * up with the pill rather than with its top edge.
 */
internal fun thumbCentrePx(fraction: Float, trackHeightPx: Float, thumbHeightPx: Float): Float =
    thumbTopPx(fraction, trackHeightPx, thumbHeightPx) + thumbHeightPx / 2f

/**
 * The fraction change produced by a drag of [deltaPx]. Measured over the thumb's *travel*, not the
 * whole track, so the pill stays under the finger instead of lagging behind it.
 */
internal fun dragFractionDelta(deltaPx: Float, trackHeightPx: Float, thumbHeightPx: Float): Float =
    deltaPx / thumbTravelPx(trackHeightPx, thumbHeightPx).coerceAtLeast(1f)

/** The section whose track position is nearest [fraction] — the one the rail emphasises. */
internal fun activeSectionFor(sections: List<DateAnchor>, fraction: Float, itemCount: Int): Int {
    var best = 0
    var bestDistance = Float.MAX_VALUE
    sections.forEachIndexed { i, section ->
        val distance = abs(trackFraction(section.index, itemCount) - fraction)
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

/**
 * The last anchor at or before [itemIndex] — the day the drag bubble names. Anchors are ascending by
 * index, so this binary-searches: there is one anchor per day with photos, which can run to
 * thousands, and the bubble asks on every drag frame. Falls back to the newest anchor when the grid
 * sits above the first one, matching how the rail has always resolved a position.
 */
internal fun bubbleAnchorFor(anchors: List<DateAnchor>, itemIndex: Int): DateAnchor? {
    if (anchors.isEmpty()) return null
    var low = 0
    var high = anchors.size - 1
    var result = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (anchors[mid].index <= itemIndex) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return anchors[result]
}

/**
 * Google-Photos-style date scrubber: invisible at rest, fading in a slim proportional pill, tick
 * marks and month/year labels while scrolling, and — while the pill is dragged — a date bubble that
 * rides with it as the grid scrubs through the already-loaded pages.
 *
 * The whole overlay spans the full width so labels can run leftwards over the photos; only the
 * narrow rail on the right takes touches, so the grid underneath stays fully tappable.
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
    val density = LocalDensity.current
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

    val minThumbPx = with(density) { MIN_THUMB_HEIGHT.toPx() }
    val thumbHeightPx = (thumb.second * trackHeightPx).coerceAtLeast(minThumbPx)

    // ---- Scrub state: the finger's track fraction while dragging, else the scroll-derived one. ----
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }
    val rawFraction = scrubFraction ?: thumb.first
    // Glide between scroll-driven positions, but track the finger exactly while dragging.
    val glidedFraction by animateFloatAsState(targetValue = rawFraction, label = "scrubGlide")
    val fraction = if (dragging) rawFraction else glidedFraction

    val itemCount = gridState.layoutInfo.totalItemsCount
    val activeIndex = activeSectionFor(sections, fraction, itemCount)
    val window by remember(sections) {
        derivedStateOf {
            DateRailWindow.window(
                sections.size,
                activeSection(sections, gridState),
                SCRUB_SECTION_WINDOW,
            )
        }
    }

    val thumbWidth by animateDpAsState(
        targetValue = if (dragging) THUMB_WIDTH_DRAGGING else THUMB_WIDTH,
        label = "scrubThumbWidth",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (dragging) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        },
        label = "scrubThumbColor",
    )
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val labelActiveColor = MaterialTheme.colorScheme.onSurface
    val labelIdleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) },
    ) {
        // ---- Section labels + tick marks, pinned to the thumb's centre line. ----
        Box(modifier = Modifier.fillMaxSize().alpha(overlayAlpha)) {
            sections.subList(window.first, window.last + 1).forEachIndexed { offset, section ->
                val sectionIndex = window.first + offset
                val isActive = sectionIndex == activeIndex
                val date = LocalDate.ofEpochDay(section.epochDay)
                val label = when (granularity) {
                    SectionGranularity.Month -> date.format(sectionMonth)
                    SectionGranularity.Year -> date.format(sectionYear)
                }
                // A zero-height row keeps the label vertically centred on the thumb's centre: the row
                // is placed at that Y, and the label centres itself on the row. The row spans the full
                // width so a long label like "Sep 2022" is never squeezed into the narrow rail.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.dp)
                        .offset {
                            IntOffset(
                                0,
                                thumbCentrePx(trackFraction(section.index, itemCount), trackHeightPx, thumbHeightPx)
                                    .roundToInt(),
                            )
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                            color = if (isActive) labelActiveColor else labelIdleColor,
                            maxLines = 1,
                            modifier = Modifier.padding(end = spacing.sm),
                        )
                        Box(
                            modifier = Modifier
                                .width(TICK_WIDTH)
                                .height(TICK_HEIGHT)
                                .background(tickColor, RoundedCornerShape(percent = 50)),
                        )
                    }
                }
            }
        }

        // ---- The rail: the only part that takes touches, so the grid stays tappable beside it. ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                .semantics { contentDescription = railDescription }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val next = (scrubFraction ?: thumb.first) +
                            dragFractionDelta(delta, trackHeightPx, thumbHeightPx)
                        val clamped = next.coerceIn(0f, 1f)
                        scrubFraction = clamped
                        val target = scrubIndex(clamped, gridState.layoutInfo.totalItemsCount)
                        if (target != gridState.firstVisibleItemIndex) {
                            scrubJob?.cancel()
                            scrubJob = scope.launch { gridState.scrollToItem(target) }
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
            // The pill: doubles as the drag handle, growing and darkening while held.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = spacing.xs)
                    .offset { IntOffset(0, thumbTopPx(fraction, trackHeightPx, thumbHeightPx).roundToInt()) }
                    .width(thumbWidth)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .alpha(overlayAlpha)
                    .background(thumbColor, RoundedCornerShape(percent = 50)),
            )
        }

        // ---- The date bubble: only while dragging, riding the thumb. ----
        val bubbleAlpha by animateFloatAsState(
            targetValue = if (dragging) 1f else 0f,
            label = "scrubBubbleAlpha",
        )
        if (bubbleAlpha > 0.01f) {
            bubbleAnchorFor(anchors, scrubIndex(fraction, itemCount))?.let { anchor ->
                val bubbleDate = LocalDate.ofEpochDay(anchor.epochDay)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.dp)
                        .offset {
                            IntOffset(
                                0,
                                thumbCentrePx(fraction, trackHeightPx, thumbHeightPx).roundToInt(),
                            )
                        },
                ) {
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = BUBBLE_END_PADDING)
                            .alpha(bubbleAlpha),
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
                            modifier = Modifier.padding(
                                horizontal = spacing.lg,
                                vertical = spacing.sm,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** The section covering the grid's first visible item (newest-first anchors). */
private fun activeSection(sections: List<DateAnchor>, gridState: LazyGridState): Int {
    val firstVisible = gridState.firstVisibleItemIndex
    return sections.indices.lastOrNull { sections[it].index <= firstVisible } ?: 0
}

/** Width of the touch/rail strip on the right edge. */
private val RAIL_WIDTH = 28.dp
private val THUMB_WIDTH = 6.dp
private val THUMB_WIDTH_DRAGGING = 10.dp
private val MIN_THUMB_HEIGHT = 40.dp
private val TICK_WIDTH = 6.dp
private val TICK_HEIGHT = 1.dp

/** Clear of the rail so the bubble never covers the pill it is describing. */
private val BUBBLE_END_PADDING = 32.dp
