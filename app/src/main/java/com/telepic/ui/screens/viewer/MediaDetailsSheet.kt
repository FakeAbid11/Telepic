package com.telepic.ui.screens.viewer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.telepic.R
import com.telepic.ui.theme.TelepicTokens
import java.text.DateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The Viewer's Details sheet (PRD §19): a bottom sheet listing reliably-obtainable metadata for the
 * current local item — name, format, size, dimensions, EXIF capture time, camera make/model and GPS —
 * and omits any field that cannot be obtained. It never fabricates a value and never presents the
 * file's library/modified date as the actual capture time. Renders a spinner while EXIF is loading.
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
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.details_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (details == null) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = spacing.lg))
            } else {
                details.displayRows(context).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // The coordinate is already readable above; this makes it actionable. Offered only
                // when the item genuinely carries one, so the action can never lead to a fabricated pin.
                details.mappableLocation()?.let { (latitude, longitude) ->
                    if (onShowOnMap != null) {
                        TextButton(onClick = { onShowOnMap(latitude, longitude) }) {
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

/** One label/value line for the Details sheet. */
data class DetailRow(val labelRes: Int, val value: String)

/**
 * Builds the presentable rows, including only fields with a reliable value. Pure enough to unit-test
 * (it reads only the [MediaDetails] fields + the device locale); the sheet shows "Unknown" for the
 * two always-present fields when their source is blank, and omits optional fields entirely.
 */
internal fun MediaDetails.displayRows(context: Context): List<DetailRow> {
    val rows = mutableListOf<DetailRow>()
    rows += DetailRow(R.string.details_file_name, fileName?.takeIf { it.isNotBlank() } ?: unknown(context))
    rows += DetailRow(R.string.details_format, mimeType?.takeIf { it.isNotBlank() } ?: unknown(context))
    if (width > 0 && height > 0) {
        rows += DetailRow(R.string.details_dimensions, context.getString(R.string.details_dimensions_value, width, height))
    }
    if (sizeBytes > 0) {
        rows += DetailRow(R.string.details_size, android.text.format.Formatter.formatShortFileSize(context, sizeBytes))
    }
    // Capture time only from EXIF — never the file-modified/library date masquerading as the shot time.
    captureMillis?.let {
        rows += DetailRow(R.string.details_taken, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it))
    }
    // The date the item entered the device library (MediaStore DATE_ADDED), when available.
    libraryAddedMillis?.let {
        rows += DetailRow(R.string.details_date_added, DateFormat.getDateInstance(DateFormat.MEDIUM).format(it))
    }
    cameraMake?.let { make ->
        val model = cameraModel
        val value = if (model != null && model.isNotBlank()) "$make $model" else make
        rows += DetailRow(R.string.details_camera, value)
    }
    mappableLocation()?.let { (latitude, longitude) ->
        rows += DetailRow(R.string.details_location, formatCoordinate(latitude, longitude))
    }
    return rows
}

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
