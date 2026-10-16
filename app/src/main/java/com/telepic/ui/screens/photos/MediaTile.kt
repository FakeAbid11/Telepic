package com.telepic.ui.screens.photos

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.DurationLabel
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaDay
import com.telepic.domain.media.MediaType
import com.telepic.ui.components.rememberSharedMediaModifier
import com.telepic.ui.components.rememberTelepicHaptics
import com.telepic.ui.theme.TelepicTokens

/**
 * A single media cell (Phase 8): a square, center-cropped, thumbnail-first Coil image (never a full
 * original), a video play + duration or a GIF badge, and a compact backup indicator derived from the
 * repository-provided [backupState]. The tile reads its status from the passed-in snapshot — it runs
 * no database query, hashing or network call itself, and never infers backup success on its own.
 *
 * In selection mode ([selectionActive]) a long-press or tap toggles this cell: it shows a check badge
 * and a translucent scrim when [selected], and the tap routes to [onClick] (the caller's toggle) while
 * a long-press always routes to [onLongClick]. Outside selection mode it behaves as a plain viewer cell.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(
    media: LocalMedia,
    backupState: MediaBackupVisualState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    isFavorite: Boolean = false,
    onLongClick: () -> Unit = onClick,
    onLongClickLabel: String? = null,
) {
    val spacing = TelepicTokens.spacing
    val context = LocalContext.current
    val haptics = rememberTelepicHaptics()
    // Remembered, not recomputed: building this label resolves several localized strings and formats
    // a full date, and it used to run on every recomposition of every visible cell. Keyed on the
    // context too, because the strings follow the locale/configuration.
    val label = remember(context, media, backupState, isFavorite) {
        mediaAccessibilityLabel(context, media, backupState, isFavorite)
    }
    // Named distinctly from the `selected` parameter: inside `semantics { }` the receiver also
    // exposes a `selected` property, and shadowing between the two reads as a bug.
    val tileSelected = selectionActive && selected
    // This tile is the *source* end of the Viewer's shared-element transition. Null-safe: outside the
    // navigation graph (a preview or a test) this is plain `Modifier` and the tile is unchanged.
    val sharedImageModifier = rememberSharedMediaModifier(media.id)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = label
                // Only meaningful while selecting: outside selection mode the tile is not a
                // toggle, and announcing "not selected" on every cell would be noise.
                // `this.` is explicit because the receiver's `selected` would otherwise be
                // ambiguous with the composable's `selected` parameter.
                if (selectionActive) this.selected = tileSelected
            }
            // The press committed to a selection; the tick is what tells the hand that before it lifts,
            // since the check badge only appears afterwards.
            .combinedClickable(
                onClick = onClick,
                // A tile is a button to a screen reader: without the role it is announced as a bare
                // "double tap to activate" region, indistinguishable from the decorative image it wraps.
                role = Role.Button,
                // Announced to a screen reader so long-press-to-select is reachable by someone who
                // cannot discover a gesture. Null for a caller whose long press means something else
                // (or nothing), so no label is invented for it.
                onLongClickLabel = onLongClickLabel,
                onLongClick = {
                    haptics.selectionStart()
                    onLongClick()
                },
            ),
    ) {
        AsyncImage(
            model = media.contentUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().then(sharedImageModifier),
        )

        when (media.type) {
            MediaType.VIDEO -> VideoOverlay(
                durationLabel = DurationLabel.format(media.durationMillis),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(spacing.xs),
            )
            MediaType.GIF -> GifOverlay(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(spacing.xs),
            )
            MediaType.PHOTO -> Unit
        }

        BackupBadge(
            state = backupState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(spacing.xs),
        )

        if (isFavorite) {
            FavoriteBadge(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(spacing.xs),
            )
        }

        if (selectionActive) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                )
            }
            SelectionCheck(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.xs),
            )
        }
    }
}

/**
 * A top-left check circle shown only while selecting: filled check when selected, a hollow ring
 * otherwise. Internal rather than private because the Favorites / Archive / Trash grid selects the
 * same way and must look the same doing it.
 */
@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    // `chromeScrim` (70% black), not `scrim`. In light mode `scrim` is only 40% black, so over a
    // bright photo the composite backdrop is ~#999999, where this badge's white ring measures
    // **2.85:1** — under the 3:1 floor WCAG 1.4.11 sets for a graphical object, and the ring is the
    // only thing telling the user that selection mode is on and nothing is picked yet. At 70% the
    // same ring measures 8.59:1. `BackupBadge` below was moved to this scrim for the identical
    // reason; this badge was simply missed.
    val scrim = TelepicTokens.colors.chromeScrim
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else scrim)
            .border(
                width = if (selected) 0.dp else 2.dp,
                color = if (selected) Color.Transparent else Color.White,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                // onPrimary, not White: the fill is `primary`, a light tone in the dark scheme,
                // where a white check measured 2.33:1 and was effectively invisible.
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun VideoOverlay(durationLabel: String?, modifier: Modifier = Modifier) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        if (durationLabel != null) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun GifOverlay(modifier: Modifier = Modifier) {
    val spacing = TelepicTokens.spacing
    Text(
        text = stringResource(R.string.media_gif_badge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        // `chromeScrim`, not `scrim`: this is white text on a badge over a photo. In light mode the
        // 40%-black `scrim` over a bright photo left it at 2.85:1 — under the 4.5:1 AA floor for
        // text. At 70% it measures 8.59:1. Same rule as the badges above and below.
        modifier = modifier
            .background(TelepicTokens.colors.chromeScrim, RoundedCornerShape(4.dp))
            .padding(horizontal = spacing.xs, vertical = 1.dp),
    )
}

/**
 * Bottom-right star shown on favorite tiles; the screen-level a11y label carries the state verbally.
 *
 * `chromeScrim` rather than `scrim` for the same reason as [SelectionCheck] — and it matters more
 * here, because the amber is a low-luminance tone: over a bright photo in light mode it measured
 * **1.81:1** against the 40%-black disc, well under the 3:1 floor for a graphical object. On the
 * 70% disc it measures 5.44:1.
 */
@Composable
private fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(TelepicTokens.colors.chromeScrim),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null, // merged into the tile's content description
            tint = TelepicTokens.colors.favoriteAmber,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A visually-quiet backup indicator. Only `QUEUED`, `UPLOADING`, `BACKED_UP` and `FAILED` are shown;
 * `NONE`/`CANCELLED` render nothing so the grid stays clean and media-first. Progress is genuinely
 * indeterminate when unavailable — no fabricated percentages.
 */
@Composable
private fun BackupBadge(state: MediaBackupVisualState, modifier: Modifier = Modifier) {
    if (state == MediaBackupVisualState.NONE) return
    // `chromeScrim` (70% black), not `scrim`: this badge is a 20dp disc sitting on a photo, and in
    // light mode `scrim` is only 40% black — over a bright image the effective background is
    // ~#999999, where neither a dark nor a light glyph reaches the 3:1 floor. A darker disc is what
    // makes the on-media tones below readable in both schemes.
    val scrim = TelepicTokens.colors.chromeScrim
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(scrim),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            MediaBackupVisualState.UPLOADING -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = Color.White,
                modifier = Modifier.size(14.dp),
            )
            else -> {
                val (icon, tint) = when (state) {
                    MediaBackupVisualState.BACKED_UP -> Icons.Outlined.CloudDone to Color.White
                    // On-media tones, not `colorScheme.error`/`tertiary`: this disc is over a photo,
                    // not over a theme surface, and the light scheme's dark red measured ~2.3:1 here.
                    MediaBackupVisualState.FAILED -> Icons.Outlined.CloudOff to TelepicTokens.colors.mediaErrorText
                    // Retry-exhausted: a distinct warning marker, never the quiet "queued" cloud.
                    MediaBackupVisualState.STALLED -> Icons.Outlined.ErrorOutline to TelepicTokens.colors.mediaWarningText
                    MediaBackupVisualState.QUEUED -> Icons.Outlined.CloudUpload to Color.White
                    else -> Icons.Outlined.CloudUpload to Color.White
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null, // the tile's merged content description already states it
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun mediaAccessibilityLabel(
    context: Context,
    media: LocalMedia,
    backupState: MediaBackupVisualState,
    isFavorite: Boolean,
): String {
    val typeRes = when (media.type) {
        MediaType.PHOTO -> R.string.media_type_photo
        MediaType.VIDEO -> R.string.media_type_video
        MediaType.GIF -> R.string.media_type_gif
    }
    val parts = mutableListOf(context.getString(typeRes), MediaDay.label(media.dateMillis))
    if (media.type == MediaType.VIDEO) {
        DurationLabel.format(media.durationMillis)?.let { parts += it }
    }
    if (backupState != MediaBackupVisualState.NONE) {
        parts += context.getString(backupState.descriptionRes())
    }
    // Appended last so existing prefix/substring expectations keep matching.
    if (isFavorite) {
        parts += context.getString(R.string.media_favorited)
    }
    return parts.joinToString(", ")
}

private fun MediaBackupVisualState.descriptionRes(): Int = when (this) {
    MediaBackupVisualState.BACKED_UP -> R.string.backup_state_backed_up
    MediaBackupVisualState.UPLOADING -> R.string.backup_state_uploading
    MediaBackupVisualState.QUEUED -> R.string.backup_state_queued
    MediaBackupVisualState.FAILED -> R.string.backup_state_failed
    MediaBackupVisualState.STALLED -> R.string.backup_state_stalled
    MediaBackupVisualState.NONE -> R.string.backup_state_none
}
