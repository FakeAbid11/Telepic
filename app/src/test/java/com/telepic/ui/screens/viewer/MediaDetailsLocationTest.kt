package com.telepic.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one validity rule behind both the Location row and the "Show on map" action. They must never
 * disagree: an action offered for a coordinate the sheet refuses to display would be a dead end, and
 * a displayed coordinate with no action is the gap this pins down.
 */
class MediaDetailsLocationTest {

    private fun details(latitude: Double?, longitude: Double?) = MediaDetails(
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 1024,
        width = 100,
        height = 100,
        captureMillis = null,
        libraryDateMillis = 0L,
        libraryAddedMillis = null,
        cameraMake = null,
        cameraModel = null,
        latitude = latitude,
        longitude = longitude,
    )

    @Test
    fun `a valid coordinate pair is returned`() {
        assertEquals(48.85 to 2.35, details(48.85, 2.35).mappableLocation())
    }

    @Test
    fun `southern and western coordinates are valid`() {
        assertEquals(-33.86 to -70.66, details(-33.86, -70.66).mappableLocation())
    }

    @Test
    fun `the boundary values are accepted`() {
        assertEquals(90.0 to 180.0, details(90.0, 180.0).mappableLocation())
        assertEquals(-90.0 to -180.0, details(-90.0, -180.0).mappableLocation())
    }

    @Test
    fun `a half-present pair yields no location`() {
        assertNull(details(48.85, null).mappableLocation())
        assertNull(details(null, 2.35).mappableLocation())
        assertNull(details(null, null).mappableLocation())
    }

    @Test
    fun `an out-of-range coordinate yields no location`() {
        assertNull(details(90.1, 2.35).mappableLocation())
        assertNull(details(48.85, 180.1).mappableLocation())
    }
}
