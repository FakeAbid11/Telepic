package com.telepix.data.media

import com.telepix.domain.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies MIME-based media classification (photos, videos, GIFs, and unknown fallback). */
class MediaClassifierTest {

    @Test
    fun `image types classify as photos`() {
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("image/jpeg"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("image/png"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("image/webp"))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("image/heic"))
    }

    @Test
    fun `gif classifies as gif`() {
        assertEquals(MediaType.GIF, MediaClassifier.classify("image/gif"))
        assertEquals(MediaType.GIF, MediaClassifier.classify("IMAGE/GIF"))
    }

    @Test
    fun `video types classify as video`() {
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("video/mp4"))
        assertEquals(MediaType.VIDEO, MediaClassifier.classify("video/quicktime"))
    }

    @Test
    fun `null or unknown mime falls back to photo`() {
        assertEquals(MediaType.PHOTO, MediaClassifier.classify(null))
        assertEquals(MediaType.PHOTO, MediaClassifier.classify("application/octet-stream"))
    }
}
