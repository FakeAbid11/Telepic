package com.telepic.ui.screens.albums

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.media.AlbumRepository
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.Album
import com.telepic.domain.media.CustomAlbum
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.domain.media.PhotosItem
import com.telepic.navigation.AlbumSource
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-level coverage for the album contents screen: the header carries the title the route
 * handed in (never a percent-escaped one), the folder and custom-album empty states each tell
 * their own honest story, and a tile tap reports the exact media back to the navigator. Fakes
 * mirror [AlbumContentsViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AlbumContentsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class EmptyAlbumRepository : AlbumRepository {
        override suspend fun albums(): List<Album> = emptyList()
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    /** Serves a fixed grid, so tile-level assertions do not need a real loader. */
    private class ItemsAlbumRepository(private val items: List<PhotosItem>) : AlbumRepository {
        override suspend fun albums(): List<Album> = emptyList()
        override fun mediaInBucket(bucketId: Long): Flow<PagingData<PhotosItem>> =
            flowOf(PagingData.from(items))
        override fun refresh() = Unit
    }

    private class EmptyStatusRepository : BackupStatusRepository {
        override val visualStates: Flow<Map<String, MediaBackupVisualState>> = flowOf(emptyMap())
    }

    private class FakeCustomAlbums : CustomAlbumRepository {
        override val albums: Flow<List<CustomAlbum>> = flowOf(emptyList())
        override suspend fun create(name: String): Long? = null
        override suspend fun rename(albumId: Long, name: String) = Unit
        override suspend fun delete(albumId: Long) = Unit
        override suspend fun addMedia(albumId: Long, mediaIds: List<Long>): Int = 0
        override suspend fun removeMedia(albumId: Long, localMediaId: Long) = Unit
        override fun observeName(albumId: Long): Flow<String?> = flowOf("Trip")
        override fun observeMediaIds(albumId: Long): Flow<List<Long>> = flowOf(emptyList())
        override suspend fun mediaIds(albumId: Long): List<Long> = emptyList()
        override fun mediaInAlbum(albumId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    private fun photo(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = MediaType.PHOTO,
        mimeType = "image/jpeg", displayName = "p$id.jpg", dateMillis = 1L, durationMillis = null,
        width = 10, height = 10, sizeBytes = 100L, bucketId = 3L, bucketName = "Camera", relativePath = "DCIM/",
    )

    private fun viewModel(
        title: String = "Trip Photos",
        albumRepository: AlbumRepository = EmptyAlbumRepository(),
    ) = AlbumContentsViewModel(
        source = AlbumSource.Bucket(3L, title),
        albumRepository = albumRepository,
        backupStatusRepository = EmptyStatusRepository(),
    )

    private fun customViewModel() = AlbumContentsViewModel(
        source = AlbumSource.Custom(7L, title = "Trip"),
        albumRepository = EmptyAlbumRepository(),
        customAlbumRepository = FakeCustomAlbums(),
        backupStatusRepository = EmptyStatusRepository(),
    )

    private fun render(
        vm: AlbumContentsViewModel,
        onBack: () -> Unit = {},
        onMediaSelected: (LocalMedia) -> Unit = {},
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                AlbumContentsScreen(viewModel = vm, onBack = onBack, onMediaSelected = onMediaSelected)
            }
        }
    }

    private fun waitUntilVisible(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `bucket album header carries the title that came from its route`() {
        render(viewModel(title = "Trip Photos"))
        composeRule.onNodeWithText("Trip Photos").assertIsDisplayed()
        waitUntilVisible("Nothing to show in this album")
        waitUntilVisible(
            "This album has no visible photos or videos. Archived and trashed items are hidden here too.",
        )
    }

    @Test
    fun `tapping a tile reports the selected media`() {
        var selected: LocalMedia? = null
        val repository = ItemsAlbumRepository(listOf(PhotosItem.Media(photo(1))))
        render(viewModel(albumRepository = repository), onMediaSelected = { selected = it })
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Photo", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Photo", substring = true)
            .onFirst().performClick()
        assertEquals(1L, selected?.id)
    }

    @Test
    fun `custom album uses the membership-editing empty copy and offers its menu`() {
        render(customViewModel())
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
        waitUntilVisible(
            "This album is empty. Select photos in your timeline, then use Add to album to put them here.",
        )
        composeRule.onNodeWithContentDescription("More actions").assertExists()
    }
}
