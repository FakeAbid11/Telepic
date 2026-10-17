package com.telepic.ui.screens.viewer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.DurationLabel
import com.telepic.domain.media.MediaType
import com.telepic.ui.theme.TelepicTokens
import java.text.DateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The Viewer's Details sheet (PRD §19): a bottom sheet listing reliably-obtainable metadata for the
 * current item — local or cloud — including its name, kind, format, size, dimensions, duration,
 * capture time, folder/path, camera and exposure (ISO / aperture / shutter / focal length), GPS,
 * backup status and, for a cloud item, its remote identity. Any field that cannot be obtained is
 * omitted rather than guessed, and the file's library/modified date is never presented as the actual
 * capture time. Renders a spinner while the details are still being assembled.
 *
 * The rows are grouped under small section labels (File / Camera / Location / Backup / Cloud), each
 * group riding on its own rounded surface, with a header that names the file and carries pills for its
 * kind and backup state — so the sheet reads as a designed panel rather than a bare list.
 *
 * When [onShowOnMap] is supplied and the item really carries a coordinate, the sheet also offers to
 * open that spot on the map — the coordinate is otherwise only readable, never actionable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheet(
    details: MediaDetails?,
    onDismiss: () -> Unit,
    onShowOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val spacing = TelepicTokens.spacing

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenMargin)
                .padding(bottom = spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.details_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            if (details == null) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = spacing.lg))
            } else {
                DetailsHeader(details)
                // One rounded surface per section, in first-seen order. `displayRows` already emits the
                // rows grouped contiguously, so `distinct` preserves the intended section order.
                val rows = details.displayRows(context)
                rows.map { it.group }.distinct().forEach { group ->
                    SectionLabel(group)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
                            rows.filter { it.group == group }.forEach { DetailRowView(it) }
                        }
                    }
                }

                // The coordinate is already readable above; this makes it actionable. Offered only
                // when the item genuinely carries one, so the action can never lead to a fabricated pin.
                details.mappableLocation()?.let { (latitude, longitude) ->
                    if (onShowOnMap != null) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Button(
                            onClick = { onShowOnMap(latitude, longitude) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Map,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(spacing.sm))
                            Text(text = stringResource(R.string.details_show_on_map))
                        }
                    }
                }
            }
        }
    }
}

/** The sheet's header: the file name over pills for its kind and backup state. */
@Composable
private fun DetailsHeader(details: MediaDetails) {
    val spacing = TelepicTokens.spacing
    val typeLabel = details.mediaType?.let { stringResource(it.labelRes) }
    val backupLabel = details.backupState?.let { stringResource(it.labelRes) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = details.fileName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.details_unknown),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (typeLabel != null || backupLabel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                typeLabel?.let { SubtitlePill(it) }
                backupLabel?.let { SubtitlePill(it) }
            }
        }
    }
}

/** A small rounded label, used for the header's kind / backup-state pills. */
@Composable
private fun SubtitlePill(text: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = TelepicTokens.spacing.sm, vertical = TelepicTokens.spacing.xxs),
        )
    }
}

/** One section's small label, above its rounded row surface. */
@Composable
private fun SectionLabel(group: DetailGroup) {
    Text(
        text = stringResource(group.labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = TelepicTokens.spacing.lg, bottom = TelepicTokens.spacing.xs),
    )
}

/** One label/value line inside a section surface. */
@Composable
private fun DetailRowView(row: DetailRow) {
    val spacing = TelepicTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(row.labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.38f),
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            // `weight`, not `fillMaxWidth`: asking for the full row width made a long value (a file
            // path, a filename) run past the label and clip. Weighted, it takes exactly the space left
            // and wraps inside it.
            modifier = Modifier.weight(1f),
        )
    }
}

/** The section a [DetailRow] belongs to. */
enum class DetailGroup(val labelRes: Int) {
    FILE(R.string.details_section_file),
    CAPTURE(R.string.details_section_capture),
    LOCATION(R.string.details_section_location),
    BACKUP(R.string.details_section_backup),
    CLOUD(R.string.details_section_cloud),
}

/** One label/value line for the Details sheet, plus the section it belongs to. */
data class DetailRow(
    val labelRes: Int,
    val value: String,
    val group: DetailGroup = DetailGroup.FILE,
)

/**
 * Builds the presentable rows, including only fields with a reliable value. Pure enough to unit-test
 * (it reads only the [MediaDetails] fields + the device locale); the sheet shows "Unknown" for the
 * two always-present fields when their source is blank, and omits optional fields entirely.
 *
 * Two rules the row order follows. Identity first — what the file *is* (name, kind, format, size,
 * dimensions, duration) before its capture data, its location, where it came from and what happened to
 * it — and rows are emitted grouped by [DetailGroup] so the sheet can draw one section per group. And
 * every exposure row is conditional: a file with no EXIF shows no ISO/aperture/shutter/focal-length
 * rows at all, rather than four lines reading "Unknown".
 */
internal fun MediaDetails.displayRows(context: Context): List<DetailRow> {
    val rows = mutableListOf<DetailRow>()
    // File — what the item is and where its bytes live.
    rows += DetailRow(R.string.details_file_name, fileName?.takeIf { it.isNotBlank() } ?: unknown(context))
    mediaType?.let { rows += DetailRow(R.string.details_type, context.getString(it.labelRes)) }
    rows += DetailRow(R.string.details_format, mimeType?.takeIf { it.isNotBlank() } ?: unknown(context))
    if (width > 0 && height > 0) {
        rows += DetailRow(R.string.details_dimensions, context.getString(R.string.details_dimensions_value, width, height))
    }
    if (sizeBytes > 0) {
        rows += DetailRow(R.string.details_size, android.text.format.Formatter.formatShortFileSize(context, sizeBytes))
    }
    // Only a video has a duration; DurationLabel already returns null for a zero/unknown one.
    DurationLabel.format(durationMillis)?.let {
        rows += DetailRow(R.string.details_duration, it)
    }
    // The date the item entered the device library (MediaStore DATE_ADDED), when available.
    libraryAddedMillis?.let {
        rows += DetailRow(R.string.details_date_added, DateFormat.getDateInstance(DateFormat.MEDIUM).format(it))
    }
    bucketName?.takeIf { it.isNotBlank() }?.let {
        rows += DetailRow(R.string.details_folder, it)
    }
    relativePath?.takeIf { it.isNotBlank() }?.let {
        rows += DetailRow(R.string.details_path, it)
    }
    // Camera & capture — EXIF only, and only from EXIF: the file-modified date never masquerades as
    // the capture time.
    captureMillis?.let {
        rows += DetailRow(
            R.string.details_taken,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it),
            DetailGroup.CAPTURE,
        )
    }
    cameraMake?.let { make ->
        val model = cameraModel
        val value = if (model != null && model.isNotBlank()) "$make $model" else make
        rows += DetailRow(R.string.details_camera, value, DetailGroup.CAPTURE)
    }
    iso?.let { rows += DetailRow(R.string.details_iso, it.toString(), DetailGroup.CAPTURE) }
    // The `f/` is part of the notation [formatAperture] produces, so the row takes its output as-is.
    // Wrapping it in a "f/%1$s" format string is what rendered "f/f/2.8" — the prefix belongs to the
    // value, not to the label. The exposure and focal-length rows below are the opposite case: their
    // unit is a suffix the label owns, so those do go through a format string.
    fNumber?.let { aperture ->
        formatAperture(aperture)?.let { rows += DetailRow(R.string.details_aperture, it, DetailGroup.CAPTURE) }
    }
    exposureTimeSeconds?.let { exposure ->
        formatExposureTime(exposure)?.let {
            rows += DetailRow(
                R.string.details_exposure,
                context.getString(R.string.details_exposure_value, it),
                DetailGroup.CAPTURE,
            )
        }
    }
    focalLengthMm?.let { focal ->
        formatFocalLength(focal)?.let {
            rows += DetailRow(
                R.string.details_focal_length,
                context.getString(R.string.details_focal_length_value, it),
                DetailGroup.CAPTURE,
            )
        }
    }
    mappableLocation()?.let { (latitude, longitude) ->
        rows += DetailRow(R.string.details_location, formatCoordinate(latitude, longitude), DetailGroup.LOCATION)
    }
    // Where the item actually lives. Always shown: for a local item it says so, and for a cloud item
    // it is the one row that explains why the EXIF rows above are absent.
    rows += DetailRow(
        R.string.details_source,
        context.getString(if (isCloud) R.string.viewer_source_cloud else R.string.viewer_source_local),
        DetailGroup.BACKUP,
    )
    // Omitted when the queue has no row for the item — see MediaDetails.backupState for why an absent
    // row must not be rendered as "Not backed up".
    backupState?.let {
        rows += DetailRow(R.string.details_backup, context.getString(it.labelRes), DetailGroup.BACKUP)
    }
    if (isCloud) {
        cloudMessageId?.let {
            rows += DetailRow(R.string.details_telegram_message, it.toString(), DetailGroup.CLOUD)
        }
        rows += DetailRow(
            R.string.details_cloud_original,
            context.getString(
                if (cloudOriginalDownloaded) {
                    R.string.details_cloud_original_downloaded
                } else {
                    R.string.details_cloud_original_remote
                },
            ),
            DetailGroup.CLOUD,
        )
    }
    return rows
}

/** Label resource for a media kind, reusing the wording the grids and badges already use. */
private val MediaType.labelRes: Int
    get() = when (this) {
        MediaType.PHOTO -> R.string.media_type_photo
        MediaType.VIDEO -> R.string.media_type_video
        MediaType.GIF -> R.string.media_type_gif
    }

/**
 * Label resource for a backup state. Deliberately the same six strings the grid badges use, so the
 * sheet and the tile can never describe the same item with different words.
 */
private val MediaBackupVisualState.labelRes: Int
    get() = when (this) {
        MediaBackupVisualState.NONE -> R.string.backup_state_none
        MediaBackupVisualState.QUEUED -> R.string.backup_state_queued
        MediaBackupVisualState.UPLOADING -> R.string.backup_state_uploading
        MediaBackupVisualState.BACKED_UP -> R.string.backup_state_backed_up
        MediaBackupVisualState.FAILED -> R.string.backup_state_failed
        MediaBackupVisualState.STALLED -> R.string.backup_state_stalled
    }

/**
 * The aperture as photographers write it — `f/2.8` — with a trailing `.0` trimmed so f/2 reads as
 * `f/2` rather than `f/2.0`. Null for a non-positive or non-finite value.
 */
internal fun formatAperture(fNumber: Double): String? {
    if (!fNumber.isFinite() || fNumber <= 0.0) return null
    return "f/${trimTrailingZero(fNumber)}"
}

/**
 * The conventional shutter notation: a sub-second exposure as `1/250`, anything else in whole seconds
 * to one decimal. Null for a non-positive or non-finite value.
 *
 * The reciprocal is derived rather than read because EXIF stores the shutter as a rational and
 * [androidx.exifinterface.media.ExifInterface] only exposes the decimal — `0.004` becomes `1/250`,
 * which is what a camera UI shows and what a reader can actually interpret.
 */
internal fun formatExposureTime(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0.0) return null
    if (seconds < 1.0) return "1/${Math.round(1.0 / seconds)}"
    return trimTrailingZero(seconds)
}

/** Focal length in millimetres to one decimal, trailing `.0` trimmed. Null for a non-positive value. */
internal fun formatFocalLength(millimetres: Double): String? {
    if (!millimetres.isFinite() || millimetres <= 0.0) return null
    return trimTrailingZero(millimetres)
}

/**
 * One decimal, ASCII digits, with a `.0` suffix removed. `Locale.US` is deliberate: the digits must
 * not follow a locale that writes a comma for the decimal separator, because these values are read
 * alongside `f/` and `mm` as notation rather than as prose.
 */
private fun trimTrailingZero(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

/**
 * The item's coordinate when it is one that can genuinely be placed on a map, else null. Single
 * source of truth for both the Details row and the "show on map" action, so the two can never
 * disagree about whether a location exists.
 */
internal fun MediaDetails.mappableLocation(): Pair<Double, Double>? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (abs(lat) > 90.0 || abs(lon) > 180.0) return null
    return lat to lon
}

/** Human-readable coordinates to ~6 decimals (~0.1 m) without an external geocoding API. */
internal fun formatCoordinate(latitude: Double, longitude: Double): String {
    val ns = if (latitude >= 0) "N" else "S"
    val ew = if (longitude >= 0) "E" else "W"
    return String.format(
        Locale.US,
        "%.6f%s, %.6f%s",
        kotlin.math.abs(latitude), ns, kotlin.math.abs(longitude), ew,
    )
}

private fun unknown(context: Context): String = context.getString(R.string.details_unknown)
