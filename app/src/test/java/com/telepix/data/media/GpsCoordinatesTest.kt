package com.telepix.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EXIF DMS → decimal GPS math: parsing, hemisphere, validation and malformed-data safety. */
class GpsCoordinatesTest {

    @Test
    fun `parses a north-east coordinate to decimal`() {
        val lat = GpsCoordinates.toDecimal(51.0, 30.0, 0.0, "N")
        val lon = GpsCoordinates.toDecimal(0.0, 7.0, 10.0, "E")
        assertNotNull(lat)
        assertEquals(51.5, lat!!, 1e-9)
        assertEquals(0.119444444, lon!!, 1e-6)
    }

    @Test
    fun `south and west are negative`() {
        assertEquals(-33.9, GpsCoordinates.toDecimal(33.0, 54.0, 0.0, "S")!!, 1e-6)
        assertTrue((GpsCoordinates.toDecimal(151.0, 12.0, 0.0, "W") ?: 0.0) < 0.0)
    }

    @Test
    fun `missing or invalid reference is rejected`() {
        assertNull(GpsCoordinates.toDecimal(10.0, 0.0, 0.0, null))
        assertNull(GpsCoordinates.toDecimal(10.0, 0.0, 0.0, "X"))
        assertNull(GpsCoordinates.toDecimal(10.0, 0.0, 0.0, ""))
    }

    @Test
    fun `non-finite or out-of-range components are rejected`() {
        assertNull(GpsCoordinates.toDecimal(Double.NaN, 0.0, 0.0, "N"))
        assertNull(GpsCoordinates.toDecimal(0.0, 61.0, 0.0, "N")) // minutes > 60
        assertNull(GpsCoordinates.toDecimal(0.0, 0.0, 61.0, "N")) // seconds > 60
    }

    @Test
    fun `a full location validates the latitude and longitude ranges`() {
        val ok = GpsCoordinates.location(
            latitudeDegrees = 48.0, latitudeMinutes = 51.0, latitudeSeconds = 0.0, latitudeRef = "N",
            longitudeDegrees = 2.0, longitudeMinutes = 21.0, longitudeSeconds = 0.0, longitudeRef = "E",
        )
        assertNotNull(ok)
        assertEquals(48.85, ok!!.latitude, 1e-6)

        val outOfRange = GpsCoordinates.location(
            latitudeDegrees = 100.0, latitudeMinutes = 0.0, latitudeSeconds = 0.0, latitudeRef = "N",
            longitudeDegrees = 0.0, longitudeMinutes = 0.0, longitudeSeconds = 0.0, longitudeRef = "E",
        )
        assertNull(outOfRange) // 100°N is impossible -> never a fabricated pin
    }
}
