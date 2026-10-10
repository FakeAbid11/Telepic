package com.telepic.data.media

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.telepic.domain.media.GeoLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a media item's embedded GPS position. Seam so the map pipeline can be tested with a fake
 * while the real EXIF decode stays in one Android-bound class. Returns null for any item without a
 * valid, in-range coordinate — never a fabricated location.
 */
interface LocationExtractor {
    suspend fun extract(uri: Uri): GeoLocation?
}

/**
 * Decodes GPS from EXIF via [ExifInterface], reading only enough of the file for the metadata (not a
 * full-resolution decode) on [Dispatchers.IO]. Missing, unreadable or malformed GPS yields `null`.
 */
class AndroidLocationExtractor(context: Context) : LocationExtractor {

    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun extract(uri: Uri): GeoLocation? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val out = FloatArray(2)
                if (exif.getLatLong(out)) {
                    GeoLocation(out[0].toDouble(), out[1].toDouble()).takeIf { it.isValid }
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
