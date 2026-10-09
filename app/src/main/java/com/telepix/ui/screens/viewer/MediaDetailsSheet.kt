package com.telepix.ui.screens.viewer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.telepix.R
import com.telepix.ui.theme.TelepixTokens
import java.text.DateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The Viewer's Details sheet (PRD §19): a bottom sheet listing reliably-obtainable metadata for the
 * current local item — name, format, size, dimensions, EXIF capture time, camera make/model and GPS —
 * and omits any field that cannot be obtained. It never fabricates a value and never presents the
 * file's library/modified date as the actual capture time. Renders a spinner while EXIF is loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheet(details: MediaDetails?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val spacing = TelepixTokens.spacing

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
    cameraMake?.let { make ->
        val model = cameraModel
        val value = if (model != null && model.isNotBlank()) "$make $model" else make
        rows += DetailRow(R.string.details_camera, value)
    }
    latitude?.let { lat ->
        longitude?.let { lon ->
            if (abs(lat) <= 90.0 && abs(lon) <= 180.0) {
                rows += DetailRow(R.string.details_location, formatCoordinate(lat, lon))
            }
        }
    }
    return rows
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
