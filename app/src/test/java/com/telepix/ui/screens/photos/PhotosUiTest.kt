package com.telepix.ui.screens.photos

import android.net.Uri
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepix.data.media.LocalMediaRepository
import com.telepix.data.media.LocalMediaRepositoryImpl
import com.telepix.data.media.MediaChangeWatcher
import com.telepix.data.media.MediaPageLoader
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaDay
import com.telepix.domain.media.MediaType
import com.telepix.permissions.MediaPermissionState
import com.telepix.ui.theme.TelepixTheme
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

    private fun photosScreen(viewModel: PhotosViewModel, permission: MediaPermissionState) {
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                PhotosScreen(
                    viewModel = viewModel,
                    onMediaSelected = {},
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

        composeRule.onNodeWithText(MediaDay.label(dayA)).assertExists()
        composeRule.onNodeWithText("0:24").assertExists()
        composeRule.onNodeWithText("GIF").assertExists()
    }
}
