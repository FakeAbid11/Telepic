package com.telepic.ui.onboarding.components

import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 *
 * The second half pins the other half of "which folder am I choosing": a bucket's name is only its
 * last path segment, so two real folders can both read "Camera". The path has to appear for exactly
 * those rows — enough to disambiguate, without adding a line to every row in the list.
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

    private fun folder(
        bucketId: Long,
        title: String,
        relativePath: String?,
        count: Int = 4,
    ) = Album(
        bucketId = bucketId,
        title = title,
        coverUri = Uri.parse("content://media/external/images/media/$bucketId"),
        coverIsVideo = false,
        count = count,
        latestMillis = 1_700_000_000_000L - bucketId,
        relativePath = relativePath,
    )

    private fun dialog(
        initiallySelected: Set<Long>,
        folders: List<Album> = listOf(camera),
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                FolderPickerDialog(
                    folders = folders,
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

    @Test
    fun `two folders sharing a name are told apart by their path`() {
        dialog(
            initiallySelected = emptySet(),
            folders = listOf(
                folder(1L, "Camera", "DCIM/Camera/"),
                folder(2L, "Camera", "Pictures/Camera/"),
            ),
        )

        // Both rows read "Camera"; without the path the user is choosing blind.
        composeRule.onNodeWithText("DCIM/Camera/").assertExists()
        composeRule.onNodeWithText("Pictures/Camera/").assertExists()
    }

    @Test
    fun `a uniquely named folder does not repeat its own path`() {
        dialog(
            initiallySelected = emptySet(),
            folders = listOf(folder(1L, "Screenshots", "Pictures/Screenshots/")),
        )

        // The path adds nothing when the name is already unique, so the row stays one line.
        composeRule.onNodeWithText("Pictures/Screenshots/").assertDoesNotExist()
    }

    @Test
    fun `a same-named pair still renders when no path is available`() {
        // API 26–28: RELATIVE_PATH does not exist, so both albums have a null path. The rows must
        // still render and stay independently toggleable — a missing path is not a crash.
        dialog(
            initiallySelected = setOf(1L),
            folders = listOf(
                folder(1L, "Camera", relativePath = null),
                folder(2L, "Camera", relativePath = null),
            ),
        )

        composeRule.onAllNodesWithText("Camera").assertCountEquals(2)
    }
}
