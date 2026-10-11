package com.telepic.ui.onboarding.components

import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.telepic.domain.media.Album
import com.telepic.ui.theme.TelepicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a folder row tells a screen reader. The row — not the Checkbox nested inside it — is the
 * single toggle, so the album name, its count and its state are announced together. A plain clickable
 * Row left the Checkbox as a second focus stop that said "checked" with nothing to say *what* was
 * checked, so these tests pin the merged toggle rather than trusting the visual.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class FolderPickerDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val camera = Album(
        bucketId = 1L,
        title = "Camera",
        coverUri = Uri.parse("content://media/external/images/media/1"),
        coverIsVideo = false,
        count = 12,
        latestMillis = 1_700_000_000_000L,
    )

    private fun dialog(initiallySelected: Set<Long>) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                FolderPickerDialog(
                    folders = listOf(camera),
                    initiallySelected = initiallySelected,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
    }

    /** The row merges its label, count and checkbox, so the album title finds that one node. */
    private fun row() = composeRule.onNodeWithText(camera.title)

    @Test
    fun `an unselected folder row is one toggle in the off state`() {
        dialog(initiallySelected = emptySet())

        row().assertIsToggleable().assertIsOff()
    }

    @Test
    fun `a preselected folder row is one toggle in the on state`() {
        dialog(initiallySelected = setOf(camera.bucketId))

        row().assertIsToggleable().assertIsOn()
    }

    @Test
    fun `the toggle carries the checkbox role`() {
        dialog(initiallySelected = emptySet())

        row().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }
}
