package com.telepix.data.media

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.telepix.domain.media.GeoLocation
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Display-safe metadata for a single media item, extracted from EXIF without a full decode. Every
 * field is nullable and only populated when reliably present — nothing here is fabricated, and a
 * corrupt/unsupported/permission-denied read yields the empty [MediaMetadata], never an error.
 */
data class MediaMetadata(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /** True capture time (EXIF DateTimeOriginal) in epoch millis, or null when the file has none. */
    val captureMillis: Long? = null,
    val location: GeoLocation? = null,
)

/** Seam so the Viewer's Details flow is unit-testable with a fake while the real EXIF read stays here. */
interface MediaMetadataReader {
    suspend fun read(uri: Uri): MediaMetadata
}

/**
 * Reads EXIF metadata (make/model, capture time, GPS) via [ExifInterface], opening the stream only
 * for the metadata header — never a full-resolution image decode — on [Dispatchers.IO]. Missing
 * sections simply stay null; the caller decides what to show. It never confuses the file's modified
 * time with the actual capture time: [MediaMetadata.captureMillis] is EXIF-only.
 */
class AndroidMediaMetadataReader(context: Context) : MediaMetadataReader {

    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun read(uri: Uri): MediaMetadata = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                MediaMetadata(
                    cameraMake = exif.safeAttribute(ExifInterface.TAG_MAKE),
                    cameraModel = exif.safeAttribute(ExifInterface.TAG_MODEL),
                    captureMillis = parseExifDateTime(exif.safeAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
                    location = if (exif.getLatLong(latLong)) {
                        GeoLocation(latLong[0].toDouble(), latLong[1].toDouble()).takeIf { it.isValid }
                    } else null,
                )
            } ?: MediaMetadata()
        } catch (_: Throwable) {
            // Corrupt file, unsupported format, or a stream we cannot open → empty, never crash/fake.
            MediaMetadata()
        }
    }

    private fun ExifInterface.safeAttribute(tag: String): String? =
        runCatching { getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
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
