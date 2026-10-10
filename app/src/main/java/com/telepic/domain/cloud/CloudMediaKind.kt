package com.telepic.domain.cloud

import java.util.Locale

/**
 * Classifies a remote MIME type into a [CloudMediaType], or `null` when the media is unsupported
 * (so the scanner can safely ignore it). GIF is kept distinct from ordinary images.
 */
object CloudMediaKind {

    fun classify(mimeType: String?, knownGif: Boolean = false): CloudMediaType? {
        if (knownGif) return CloudMediaType.GIF
        val normalized = mimeType?.lowercase(Locale.US) ?: return null
        return when {
            normalized == "image/gif" -> CloudMediaType.GIF
            normalized.startsWith("video/") -> CloudMediaType.VIDEO
            normalized.startsWith("image/") -> CloudMediaType.IMAGE
            else -> null
        }
    }
}
