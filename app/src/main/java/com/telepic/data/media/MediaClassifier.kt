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

    /**
     * The one MIME type MediaStore reports for an animated GIF.
     *
     * Public because it is also the value the timeline's hide-GIFs filter has to compare against in
     * SQL (`TimelineMediaFilter`). MediaStore has no `MEDIA_TYPE` for GIFs — they are images — so the
     * MIME string is the only identity, and the classifier and the query filter must not drift apart.
     */
    const val GIF_MIME_TYPE = "image/gif"

    fun classify(mimeType: String?): MediaType {
        val normalized = mimeType?.lowercase(Locale.US) ?: return MediaType.PHOTO
        return when {
            normalized == GIF_MIME_TYPE -> MediaType.GIF
            normalized.startsWith("video/") -> MediaType.VIDEO
            normalized.startsWith("image/") -> MediaType.PHOTO
            else -> MediaType.PHOTO
        }
    }
}
