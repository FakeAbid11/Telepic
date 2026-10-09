package com.telepix.ui.screens.albums

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import com.telepix.data.media.AlbumRepository
import com.telepix.domain.media.Album
import com.telepix.domain.media.PhotosItem
import com.telepix.permissions.MediaPermissionState
import com.telepix.ui.theme.TelepixTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AlbumsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun album(id: Long, name: String) = Album(
        bucketId = id, title = name, coverUri = Uri.parse("content://media/external/images/media/$id"),
        coverIsVideo = false, count = 4, latestMillis = 1_700_000_000_000L - id,
    )

    private class FakeAlbums(private val items: List<Album>) : AlbumRepository {
        override suspend fun albums(): List<Album> = items
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
    }

    @Test
    fun `album grid shows names and counts and opens on tap`() {
        var opened: Album? = null
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                AlbumsScreen(
                    viewModel = AlbumsViewModel(FakeAlbums(listOf(album(1, "Camera"), album(2, "Screenshots")))),
                    onOpenAlbum = { opened = it },
                    onOpenOrganization = {},
                    permissionStateOverride = MediaPermissionState.Granted,
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Open album Camera").fetchSemanticsNodes().isNotEmpty()
        }
        // Cards merge their children into one accessibility node, so album titles live in the unmerged
        // tree. The tiny Robolectric viewport may not compose the second album, so assert the first
        // album's rendered name + count and that tapping it opens exactly that bucket (multi-album
        // grouping/ordering is covered deterministically by AlbumGrouperTest).
        assertTrue(composeRule.onAllNodesWithText("Camera", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("4 items", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithContentDescription("Open album Camera").performClick()
        assertEquals(1L, opened?.bucketId)
    }

    @Test
    fun `denied permission shows guidance not an empty album list`() {
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                AlbumsScreen(
                    viewModel = AlbumsViewModel(FakeAlbums(emptyList())),
                    onOpenAlbum = {},
                    onOpenOrganization = {},
                    permissionStateOverride = MediaPermissionState.Denied,
                )
            }
        }
        composeRule.onNodeWithText("See your albums").assertIsDisplayed()
    }
}
