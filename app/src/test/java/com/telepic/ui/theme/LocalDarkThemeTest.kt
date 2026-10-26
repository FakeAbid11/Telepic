package com.telepic.ui.theme

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `LocalDarkTheme` is the only way a non-Compose surface — the osmdroid map — can learn whether the
 * app is dark, so the contract worth pinning is simply that it reports the caller's choice and not
 * the device's.
 *
 * That distinction is the whole reason the local exists: `isSystemInDarkTheme()` answers for the
 * *system*, while `ThemeMode` lets the user force Dark on a Light device (or the reverse), and the
 * basemap has to agree with the rest of the UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalDarkThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun reportedDarkTheme(darkTheme: Boolean): Boolean {
        var seen = false
        composeRule.setContent {
            TelepicTheme(darkTheme = darkTheme) {
                seen = LocalDarkTheme.current
            }
        }
        composeRule.waitForIdle()
        return seen
    }

    @Test
    fun `the local reports a light theme when the caller asked for light`() {
        assertEquals(false, reportedDarkTheme(darkTheme = false))
    }

    @Test
    fun `the local reports a dark theme when the caller asked for dark`() {
        assertEquals(true, reportedDarkTheme(darkTheme = true))
    }
}
