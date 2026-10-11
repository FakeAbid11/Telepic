package com.telepic.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login screen's number handling: an explicit international number always wins, a national
 * number is combined with the selected dial code, and the country table stays consistent.
 */
class PhoneNumberFormatterTest {

    @Test
    fun `an explicit international number passes through unchanged`() {
        assertEquals("+15550101234", normalizeE164("+1 555 010 1234", "+92"))
    }

    @Test
    fun `a national number is combined with the dial code`() {
        assertEquals("+923001234567", normalizeE164("300 123 4567", "+92"))
    }

    @Test
    fun `a leading trunk zero is dropped`() {
        assertEquals("+923001234567", normalizeE164("03001234567", "+92"))
    }

    @Test
    fun `non digits are stripped before combining`() {
        assertEquals("+8801712345678", normalizeE164("01712-345 678", "+880"))
    }

    @Test
    fun `blank input stays blank instead of becoming a bare dial code`() {
        assertEquals("", normalizeE164("   ", "+92"))
    }

    @Test
    fun `iso codes are unique`() {
        assertEquals(PhoneCountries.All.size, PhoneCountries.All.map { it.iso }.distinct().size)
    }

    @Test
    fun `iso lookup is case-insensitive and falls back to null`() {
        assertEquals("+92", PhoneCountries.byIso("pk")?.dialCode)
        assertEquals(null, PhoneCountries.byIso("ZZ"))
        assertEquals(null, PhoneCountries.byIso(null))
    }

    @Test
    fun `the fallback default is the united states`() {
        assertEquals("US", PhoneCountries.Default.iso)
        assertEquals("+1", PhoneCountries.Default.dialCode)
        assertTrue(PhoneCountries.All.any { it.iso == "US" })
    }
}
