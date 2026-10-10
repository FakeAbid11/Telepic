package com.telepic.data.media

import com.telepic.ui.screens.viewer.formatCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure parsing/formatting rules behind Media Details: EXIF capture timestamps are accepted only in
 * the expected form (never a fabricated value), out-of-range coordinates are rejected, and GPS is
 * rendered without any external geocoding.
 */
class MediaMetadataParseTest {

    @Test
    fun `parseExifDateTime reads the standard EXIF format`() {
        // "yyyy:MM:dd HH:mm:ss" → a non-null epoch (same value the formatter produced).
        val millis = parseExifDateTime("2024:06:15 12:30:00")
        assertEquals(true, millis != null)
    }

    @Test
    fun `parseExifDateTime returns null for missing or malformed values`() {
        assertNull(parseExifDateTime(null))
        assertNull(parseExifDateTime(""))
        assertNull(parseExifDateTime("   "))
        assertNull(parseExifDateTime("15/06/2024 12:30")) // wrong separators
        assertNull(parseExifDateTime("nonsense"))
    }

    @Test
    fun `formatCoordinate renders signed hemispheres to six decimals`() {
        assertEquals("48.850000N, 2.350000E", formatCoordinate(48.85, 2.35))
        assertEquals("33.860000S, 70.660000W", formatCoordinate(-33.86, -70.66))
    }

    @Test
    fun `mediaMetadata location is validated through GeoLocation range rules`() {
        // An out-of-range latitude is invalid → the reader's takeIf{isValid} would drop it; assert the model agrees.
        assertEquals(false, com.telepic.domain.media.GeoLocation(120.0, 0.0).isValid)
        assertEquals(true, com.telepic.domain.media.GeoLocation(48.85, 2.35).isValid)
    }
}
