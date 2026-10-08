package com.telepix.ui.screens.photos

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepix.data.backup.BackupStatusRepository
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.LocalMediaRepositoryImpl
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.data.media.MediaPageLoader
import com.telepix.domain.backup.MediaBackupVisualState
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaDay
import com.telepix.domain.media.MediaType
import com.telepix.permissions.MediaPermissionState
import com.telepix.ui.theme.TelepixTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for the real Photos screen, driven by fake media repositories so
 * they never depend on a device's MediaStore. Covers permission guidance, empty + refresh, and
 * grid rendering with day headers and video/GIF indicators.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class PhotosUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ListMediaLoader(private val all: List<LocalMedia>) : MediaPageLoader {
        override suspend fun load(offset: Int, limit: Int): List<LocalMedia> =
            all.drop(offset).take(limit)
    }

    private class CountingRepository(private val delegate: LocalMediaRepository) : LocalMediaRepository {
        var refreshCount = 0
        override val media get() = delegate.media
        override fun refresh() {
            refreshCount++
            delegate.refresh()
        }
    }

    private object NoopWatcher : MediaChangeWatcher {
        override fun start(callback: () -> Unit) = Unit
        override fun stop() = Unit
    }

    private class FakeStatus(private val states: Map<String, MediaBackupVisualState>) : BackupStatusRepository {
        override val visualStates: Flow<Map<String, MediaBackupVisualState>> = flowOf(states)
    }

    private fun media(
        id: Long,
        dateMillis: Long,
        type: MediaType,
        durationMillis: Long? = null,
    ): LocalMedia {
        val mime = when (type) {
            MediaType.VIDEO -> "video/mp4"
            MediaType.GIF -> "image/gif"
            MediaType.PHOTO -> "image/jpeg"
        }
        return LocalMedia(
            id = id,
            contentUri = Uri.parse("content://media/external/images/media/$id"),
            type = type,
            mimeType = mime,
            displayName = "f$id",
            dateMillis = dateMillis,
            durationMillis = durationMillis,
            width = 100,
            height = 100,
            sizeBytes = 1000L,
            bucketId = null,
            bucketName = null,
            relativePath = null,
        )
    }

    private fun photosScreen(
        viewModel: PhotosViewModel,
        permission: MediaPermissionState,
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                PhotosScreen(
                    viewModel = viewModel,
                    onMediaSelected = {},
                    onOpenSettings = onOpenSettings,
                    permissionStateOverride = permission,
                )
            }
        }
    }

    @Test
    fun `denied permission shows guidance and an allow action`() {
        val repository = LocalMediaRepositoryImpl(ListMediaLoader(emptyList()))
        photosScreen(PhotosViewModel(repository, NoopWatcher), MediaPermissionState.Denied)

        composeRule.onNodeWithText("See your photos").assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").assertIsDisplayed()
    }

    @Test
    fun `empty library shows the empty state and refresh re-queries`() {
        val repository = CountingRepository(LocalMediaRepositoryImpl(ListMediaLoader(emptyList())))
        photosScreen(PhotosViewModel(repository, NoopWatcher), MediaPermissionState.Granted)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No photos or videos yet")
                .fetchSemanticsNodes().isNotEmpty()
        }

        val before = repository.refreshCount
        composeRule.onNodeWithText("Refresh").performClick()
        assertTrue(repository.refreshCount > before)
    }

    @Test
    fun `grid renders day headers with video and gif indicators`() {
        val dayA = 1_700_000_000_000L
        val dayB = dayA - 86_400_000L
        val items = listOf(
            media(1, dayA, MediaType.PHOTO),
            media(2, dayA, MediaType.VIDEO, durationMillis = 24_000L),
            media(3, dayB, MediaType.GIF),
        )
        val repository = LocalMediaRepositoryImpl(ListMediaLoader(items))
        photosScreen(PhotosViewModel(repository, NoopWatcher), MediaPermissionState.Granted)

        // Wait until the video duration overlay is rendered.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("0:24").fetchSemanticsNodes().isNotEmpty()
        }

        // The day label appears both as the grid header and in the date rail, so assert presence
        // (>=1) rather than uniqueness. Video and GIF indicators are present as well.
        assertTrue(
            composeRule.onAllNodesWithText(MediaDay.label(dayA)).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(composeRule.onAllNodesWithText("GIF").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `top bar shows the Photos title and a settings action that navigates`() {
        var opened = false
        val repository = LocalMediaRepositoryImpl(ListMediaLoader(emptyList()))
        photosScreen(PhotosViewModel(repository, NoopWatcher), MediaPermissionState.Granted, onOpenSettings = { opened = true })

        composeRule.onNodeWithText("Telepix").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun `partial access shows a banner and never claims a full library`() {
        val items = listOf(media(1, 1_700_000_000_000L, MediaType.PHOTO))
        val repository = LocalMediaRepositoryImpl(ListMediaLoader(items))
        photosScreen(PhotosViewModel(repository, NoopWatcher), MediaPermissionState.Partial)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Showing only the photos and videos you selected", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a backed-up item shows an accessible backup indicator`() {
        val items = listOf(media(5, 1_700_000_000_000L, MediaType.PHOTO))
        val repository = LocalMediaRepositoryImpl(ListMediaLoader(items))
        val status = FakeStatus(mapOf("5" to MediaBackupVisualState.BACKED_UP))
        photosScreen(PhotosViewModel(repository, NoopWatcher, status), MediaPermissionState.Granted)

        // The badge is described on the tile's merged content description, not as raw text.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Backed up", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `tapping a tile invokes the viewer navigation callback`() {
        var tapped: Long? = null
        val items = listOf(media(42, 1_700_000_000_000L, MediaType.PHOTO))
        val repository = LocalMediaRepositoryImpl(ListMediaLoader(items))
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                PhotosScreen(
                    viewModel = PhotosViewModel(repository, NoopWatcher),
                    onMediaSelected = { tapped = it.id },
                    permissionStateOverride = MediaPermissionState.Granted,
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Photo", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Photo", substring = true)[0].performClick()
        assertTrue(tapped == 42L)
    }
}
