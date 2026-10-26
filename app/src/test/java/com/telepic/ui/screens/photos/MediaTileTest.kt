package com.telepic.ui.screens.photos

import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.ui.theme.TelepicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the tile tells a screen reader about selection. The check badge is only a picture of the
 * state; `Selected` is the property TalkBack actually reads, and it is easy to lose when the tile's
 * modifier chain is edited — hence these tests rather than trusting the visual.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class MediaTileTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tile(
        selected: Boolean,
        selectionActive: Boolean,
        backupState: MediaBackupVisualState = MediaBackupVisualState.NONE,
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                MediaTile(
                    media = LocalMedia(
                        id = 1,
                        contentUri = Uri.parse("content://media/external/images/media/1"),
                        type = MediaType.PHOTO,
                        mimeType = "image/jpeg",
                        displayName = "f1",
                        dateMillis = 1_700_000_000_000L,
                        durationMillis = null,
                        width = 100,
                        height = 100,
                        sizeBytes = 1_000L,
                        bucketId = null,
                        bucketName = null,
                        relativePath = null,
                    ),
                    backupState = backupState,
                    onClick = {},
                    selected = selected,
                    selectionActive = selectionActive,
                )
            }
        }
    }

    /** The tile is the one node carrying the type label; the image inside it is decorative. */
    private fun tileNode() = composeRule.onNodeWithContentDescription("Photo", substring = true)

    @Test
    fun `a selected tile reports itself as selected`() {
        tile(selected = true, selectionActive = true)

        tileNode().assertIsSelected()
    }

    @Test
    fun `an unselected tile in selection mode reports itself as not selected`() {
        tile(selected = false, selectionActive = true)

        tileNode().assertIsNotSelected()
    }

    @Test
    fun `outside selection mode the tile claims no selection state at all`() {
        tile(selected = false, selectionActive = false)

        // Absent, not merely false: the cell is not a toggle outside selection mode, so announcing
        // "not selected" on every tile would be noise.
        tileNode().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    fun `the tile is one node carrying both the description and the action`() {
        tile(selected = false, selectionActive = false)

        // The description used to sit on a *parent* of the clickable node — `combinedClickable`
        // publishes the action and the Button role on a node of its own — so the tile was two
        // accessibility stops and the actionable one had no label at all. One node must carry
        // both.
        val described = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        composeRule.onNode(described and isButton).assertExists()
    }

    /**
     * The badge itself is a picture — `BackupBadge` is private and its `Icon` carries
     * `contentDescription = null` on purpose — so the *only* thing a screen reader ever learns about
     * backup state is this label. It is appended by `mediaAccessibilityLabel`, which is easy to lose
     * when the badge's wiring is edited, hence a test rather than trust.
     */
    @Test
    fun `a backed-up tile says so in its description`() {
        tile(selected = false, selectionActive = false, backupState = MediaBackupVisualState.BACKED_UP)

        composeRule.onNodeWithContentDescription("Backed up", substring = true).assertExists()
    }

    @Test
    fun `a tile with no backup state says nothing about backup`() {
        tile(selected = false, selectionActive = false, backupState = MediaBackupVisualState.NONE)

        // Not merely "not backed up": an item the queue has never seen must not be described as
        // anything at all, or every unqueued photo announces a state it does not have.
        composeRule.onNodeWithContentDescription("Backed up", substring = true).assertDoesNotExist()
    }
}
