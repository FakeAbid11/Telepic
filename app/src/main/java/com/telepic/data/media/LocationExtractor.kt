package com.telepic.data.media

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.GeoLocation
import kotlinx.coroutines.CancellationException
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
            contentResolver.openInputStream(contentResolver.requireOriginalIfPermitted(uri))?.use { stream ->
                val exif = ExifInterface(stream)
                val out = FloatArray(2)
                if (exif.getLatLong(out)) {
                    GeoLocation(out[0].toDouble(), out[1].toDouble()).takeIf { it.isValid }
                } else {
                    null
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // Debug, not warn: a file with no readable GPS is an expected per-item outcome, and a
            // library full of them must not turn every map sweep into log spam.
            TelepicLog.debug("LocationExtractor", "no readable GPS in this item", throwable)
            null
        }
    }
}
