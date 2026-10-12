package com.telepic.ui.screens.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three exposure formatters. They turn raw EXIF numbers into the notation a camera UI shows, so
 * the cases worth pinning are the ones where the raw value and the displayed value deliberately
 * differ — a `0.004` second shutter is `1/250`, and a whole-stop `f/2.0` is `f/2` rather than `f/2.0`.
 *
 * Pure and device-free: no Robolectric, no Context.
 */
class MediaDetailsFormatTest {

    @Test
    fun `aperture reads as photographers write it`() {
        assertEquals("f/2.8", formatAperture(2.8))
        assertEquals("f/1.8", formatAperture(1.8))
        assertEquals("f/11", formatAperture(11.0))
    }

    @Test
    fun `a whole-stop aperture drops the trailing decimal`() {
        assertEquals("f/2", formatAperture(2.0))
        assertEquals("f/8", formatAperture(8.0))
    }

    @Test
    fun `a non-positive or non-finite aperture yields nothing`() {
        // EXIF writers commonly emit 0 for an unset tag; "f/0" would be a fabricated reading.
        assertNull(formatAperture(0.0))
        assertNull(formatAperture(-2.8))
        assertNull(formatAperture(Double.NaN))
    }

    @Test
    fun `a fast shutter reads as a reciprocal`() {
        assertEquals("1/250", formatExposureTime(0.004))
        assertEquals("1/1000", formatExposureTime(0.001))
        assertEquals("1/2", formatExposureTime(0.5))
    }

    @Test
    fun `a one-second-or-longer exposure reads in seconds`() {
        assertEquals("1", formatExposureTime(1.0))
        assertEquals("1.5", formatExposureTime(1.5))
        assertEquals("30", formatExposureTime(30.0))
    }

    @Test
    fun `a non-positive exposure yields nothing`() {
        assertNull(formatExposureTime(0.0))
        assertNull(formatExposureTime(-1.0))
    }

    @Test
    fun `focal length keeps one decimal and trims a whole number`() {
        assertEquals("4.3", formatFocalLength(4.3))
        assertEquals("26", formatFocalLength(26.0))
        assertEquals("5.5", formatFocalLength(5.5))
    }

    @Test
    fun `a non-positive focal length yields nothing`() {
        assertNull(formatFocalLength(0.0))
        assertNull(formatFocalLength(Double.NaN))
    }
}
