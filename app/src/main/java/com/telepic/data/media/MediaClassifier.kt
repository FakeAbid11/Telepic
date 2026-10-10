package com.telepic.data.media

import com.telepic.domain.media.MediaType
import java.util.Locale

/**
 * Normalizes a MIME type into a domain [MediaType].
 *
 * Prefers MediaStore's MIME metadata (per the PRD) over filename-extension checks, so HEIC, WebP,
 * etc. map correctly without a hard-coded extension list.
 */
object MediaClassifier {

    fun classify(mimeType: String?): MediaType {
        val normalized = mimeType?.lowercase(Locale.US) ?: return MediaType.PHOTO
        return when {
            normalized == "image/gif" -> MediaType.GIF
            normalized.startsWith("video/") -> MediaType.VIDEO
            normalized.startsWith("image/") -> MediaType.PHOTO
            else -> MediaType.PHOTO
        }
    }
}
