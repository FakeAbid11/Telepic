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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaDay
import com.telepic.domain.media.MediaType
import com.telepic.ui.theme.TelepicTokens
import java.util.Locale

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
) {
    val spacing = TelepicTokens.spacing
    val context = LocalContext.current
    val label = mediaAccessibilityLabel(context, media, backupState, isFavorite)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = label }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = media.contentUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )

        when (media.type) {
            MediaType.VIDEO -> VideoOverlay(
                durationLabel = formatDuration(media.durationMillis),
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

/** A top-left check circle shown only while selecting: filled check when selected, a hollow ring otherwise. */
@Composable
private fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val scrim = TelepicTokens.colors.scrim
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
                tint = Color.White,
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
        modifier = modifier
            .background(TelepicTokens.colors.scrim, RoundedCornerShape(4.dp))
            .padding(horizontal = spacing.xs, vertical = 1.dp),
    )
}

/** Bottom-right star shown on favorite tiles; the screen-level a11y label carries the state verbally. */
@Composable
private fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(TelepicTokens.colors.scrim),
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
    val scrim = TelepicTokens.colors.scrim
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
                    MediaBackupVisualState.FAILED -> Icons.Outlined.CloudOff to MaterialTheme.colorScheme.error
                    // Retry-exhausted: a distinct warning marker, never the quiet "queued" cloud.
                    MediaBackupVisualState.STALLED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.tertiary
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
        formatDuration(media.durationMillis)?.let { parts += it }
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

private fun formatDuration(millis: Long?): String? {
    if (millis == null || millis <= 0L) return null
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
