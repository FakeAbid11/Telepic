package com.telepic.data.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.GeoLocation
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Display-safe metadata for a single media item, extracted from EXIF + MediaStore without a full
 * decode. Every field is nullable and only populated when reliably present — nothing here is
 * fabricated, and a corrupt/unsupported/permission-denied read yields the empty [MediaMetadata],
 * never an error.
 */
data class MediaMetadata(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /** True capture time (EXIF DateTimeOriginal) in epoch millis, or null when the file has none. */
    val captureMillis: Long? = null,
    /** MediaStore DATE_ADDED (epoch seconds → millis) for "added to the library", or null. */
    val libraryAddedMillis: Long? = null,
    val location: GeoLocation? = null,
    /** EXIF PhotographicSensitivity (ISO speed). Null when absent, or when the value is not positive. */
    val iso: Int? = null,
    /** EXIF FNumber — the lens aperture, e.g. `2.8` for f/2.8. Null when absent. */
    val fNumber: Double? = null,
    /** EXIF ExposureTime in seconds (a 1/250s shutter is `0.004`). Null when absent. */
    val exposureTimeSeconds: Double? = null,
    /** EXIF FocalLength in millimetres. Null when absent. */
    val focalLengthMm: Double? = null,
)

/** Seam so the Viewer's Details flow is unit-testable with a fake while the real EXIF read stays here. */
interface MediaMetadataReader {
    suspend fun read(uri: Uri): MediaMetadata
}

/**
 * Reads EXIF metadata (make/model, capture time, GPS, and the exposure set: ISO, aperture, shutter
 * and focal length) via [ExifInterface], opening the stream only for the metadata header — never a
 * full-resolution image decode — on [Dispatchers.IO], plus a single-row MediaStore query for the
 * library-added date. Missing sections simply stay null; the caller decides what to show. It never
 * confuses the file's modified time with the actual capture time: [MediaMetadata.captureMillis] is
 * EXIF-only.
 *
 * The exposure fields are read as *numbers* rather than raw strings so the presentation layer can
 * format them (f/2.8, 1/250 s), and every one of them is discarded when it is zero or negative — EXIF
 * writers commonly emit `0/0` for an unset tag, and `0` would otherwise render as a real value.
 */
class AndroidMediaMetadataReader(context: Context) : MediaMetadataReader {

    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun read(uri: Uri): MediaMetadata = withContext(Dispatchers.IO) {
        val added = readDateAdded(uri)
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                MediaMetadata(
                    cameraMake = exif.safeAttribute(ExifInterface.TAG_MAKE),
                    cameraModel = exif.safeAttribute(ExifInterface.TAG_MODEL),
                    captureMillis = parseExifDateTime(exif.safeAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
                    libraryAddedMillis = added,
                    location = if (exif.getLatLong(latLong)) {
                        GeoLocation(latLong[0].toDouble(), latLong[1].toDouble()).takeIf { it.isValid }
                    } else null,
                    iso = exif.safeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                    fNumber = exif.safeDouble(ExifInterface.TAG_F_NUMBER),
                    exposureTimeSeconds = exif.safeDouble(ExifInterface.TAG_EXPOSURE_TIME),
                    focalLengthMm = exif.safeDouble(ExifInterface.TAG_FOCAL_LENGTH),
                )
            } ?: MediaMetadata(libraryAddedMillis = added)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // Corrupt file, unsupported format, or a stream we cannot open → keep the date, no fake.
            // Debug, not warn: an expected per-item outcome, not a failure of the read.
            TelepicLog.debug("MediaMetadata", "could not read EXIF; keeping the MediaStore date", throwable)
            MediaMetadata(libraryAddedMillis = added)
        }
    }

    /** One bounded MediaStore row for DATE_ADDED (epoch seconds); null when unavailable. */
    private fun readDateAdded(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            if (col >= 0 && cursor.moveToFirst() && !cursor.isNull(col)) {
                cursor.getLong(col) * 1000L
            } else null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        TelepicLog.debug("MediaMetadata", "DATE_ADDED unavailable for this item", throwable)
        null
    }

    private fun ExifInterface.safeAttribute(tag: String): String? =
        runCatching { getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    /**
     * A positive EXIF integer, or null. Zero and negatives are discarded rather than shown: an unset
     * tag commonly serialises as `0`, and reporting "ISO 0" would be a fabricated reading.
     */
    private fun ExifInterface.safeInt(tag: String): Int? =
        runCatching { getAttributeInt(tag, 0) }.getOrNull()?.takeIf { it > 0 }

    /** A positive EXIF rational/decimal, or null. See [safeInt] for why non-positive values are dropped. */
    private fun ExifInterface.safeDouble(tag: String): Double? =
        runCatching { getAttributeDouble(tag, 0.0) }.getOrNull()?.takeIf { it > 0.0 && it.isFinite() }
}

/**
 * Parses an EXIF timestamp ("yyyy:MM:dd HH:mm:ss", a space-separated date+time) into epoch millis.
 * Returns null for blank or malformed values. Pure and TZ-local, so it is unit-testable without a
 * device; callers must not feed it a file-modified time — only genuine EXIF capture strings.
 */
internal fun parseExifDateTime(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
    }.getOrNull()
}
