package com.telepix.ui.screens.photos

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.telepix.R
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaDay
import com.telepix.domain.media.MediaType
import com.telepix.ui.theme.TelepixTokens
import java.util.Locale

/**
 * A single media cell. Renders a thumbnail-first image via Coil (never the full original),
 * overlays a video play + duration or a GIF badge, and exposes a meaningful accessibility label.
 */
@Composable
fun MediaTile(
    media: LocalMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TelepixTokens.spacing
    val context = LocalContext.current
    val label = mediaAccessibilityLabel(context, media)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(spacing.gridItemRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = media.contentUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )

        when (media.type) {
            MediaType.VIDEO -> VideoOverlay(durationLabel = formatDuration(media.durationMillis))
            MediaType.GIF -> GifOverlay()
            MediaType.PHOTO -> Unit
        }
    }
}

@Composable
private fun VideoOverlay(durationLabel: String?) {
    val spacing = TelepixTokens.spacing
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        if (durationLabel != null) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun GifOverlay() {
    val spacing = TelepixTokens.spacing
    Text(
        text = stringResource(R.string.media_gif_badge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(spacing.xs)
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = spacing.xs, vertical = 1.dp),
    )
}

private fun mediaAccessibilityLabel(context: Context, media: LocalMedia): String {
    val typeRes = when (media.type) {
        MediaType.PHOTO -> R.string.media_type_photo
        MediaType.VIDEO -> R.string.media_type_video
        MediaType.GIF -> R.string.media_type_gif
    }
    val type = context.getString(typeRes)
    val date = MediaDay.label(media.dateMillis)
    val duration = formatDuration(media.durationMillis)
    return if (media.type == MediaType.VIDEO && duration != null) {
        "$type, $date, $duration"
    } else {
        "$type, $date"
    }
}

private fun formatDuration(millis: Long?): String? {
    if (millis == null || millis <= 0L) return null
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
