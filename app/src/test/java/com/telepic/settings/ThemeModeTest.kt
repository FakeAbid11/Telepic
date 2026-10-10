package com.telepic.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the theme preference model that drives Telepic's dark-by-default behavior and
 * the failure-tolerant parsing used when reading the persisted value.
 */
class ThemeModeTest {

    @Test
    fun `dark is the default`() {
        assertSame(ThemeMode.DARK, ThemeMode.Default)
    }

    @Test
    fun `dark mode is always dark regardless of system`() {
        assertTrue(ThemeMode.DARK.isDarkTheme(isSystemInDarkTheme = false))
        assertTrue(ThemeMode.DARK.isDarkTheme(isSystemInDarkTheme = true))
    }

    @Test
    fun `light mode is never dark regardless of system`() {
        assertFalse(ThemeMode.LIGHT.isDarkTheme(isSystemInDarkTheme = false))
        assertFalse(ThemeMode.LIGHT.isDarkTheme(isSystemInDarkTheme = true))
    }

    @Test
    fun `system mode follows the system setting`() {
        assertTrue(ThemeMode.SYSTEM.isDarkTheme(isSystemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.isDarkTheme(isSystemInDarkTheme = false))
    }

    @Test
    fun `fromKey round-trips every mode name`() {
        ThemeMode.entries.forEach { mode ->
            assertSame(mode, ThemeMode.fromKey(mode.name))
        }
    }

    @Test
    fun `fromKey is case-insensitive`() {
        assertSame(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
        assertSame(ThemeMode.SYSTEM, ThemeMode.fromKey("SyStEm"))
    }

    @Test
    fun `fromKey falls back to default for unknown or null keys`() {
        assertSame(ThemeMode.Default, ThemeMode.fromKey(null))
        assertSame(ThemeMode.Default, ThemeMode.fromKey(""))
        assertSame(ThemeMode.Default, ThemeMode.fromKey("not-a-mode"))
    }

    @Test
    fun `all three theme options are supported`() {
        assertEquals(3, ThemeMode.entries.size)
    }
}
