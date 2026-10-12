package com.telepic.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.telepic.ui.theme.TelepicTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings gear that every top-level screen puts in its top-right corner.
 *
 * Two things are pinned. Its description must be exactly "Settings" — the description lives on the
 * `Icon` only, and setting it on the `IconButton` as well made TalkBack read "Settings, Settings",
 * because the merge policy for `ContentDescription` is `Concat`; the exact-match lookup below is what
 * catches a regression there. And it must be a real button: it is now the only way into Settings from
 * Cloud, Albums and Map, so a non-clickable gear would strand those screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class SettingsActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `exposes a single Settings description and invokes its action`() {
        var clicks = 0
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                SettingsAction(onClick = { clicks++ })
            }
        }

        val gear = composeRule.onNodeWithContentDescription("Settings")
        gear.assertHasClickAction()
        gear.performClick()
        assertEquals(1, clicks)
    }
}
