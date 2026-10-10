package com.telepic.ui.screens.cloud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepic.data.cloud.CloudRepository
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cloud screen states, driven by a fake repository (no Telegram/device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class CloudScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeCloudRepository(
        status: CloudStatus,
        title: String?,
        private val mediaList: List<CloudMedia>,
    ) : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(status).asStateFlow()
        override val destinationTitle: StateFlow<String?> = MutableStateFlow(title).asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(mediaList)
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(
            request: com.telepic.domain.cloud.CloudUploadRequest,
            onProgress: (com.telepic.domain.cloud.CloudUploadProgress) -> Unit,
            onSent: suspend (Long, Long) -> Unit,
        ): com.telepic.domain.cloud.CloudUploadResult? = null
        override suspend fun confirmUpload(
            chatId: Long,
            messageId: Long,
            mediaType: com.telepic.domain.cloud.CloudMediaType,
            contentHash: String?,
            contentSizeBytes: Long?,
        ): com.telepic.domain.cloud.CloudUploadResult = throw NotImplementedError()
    }

    private fun item(id: Long, type: CloudMediaType, duration: Long? = null) = CloudMedia(
        messageId = id,
        chatId = 100L,
        mediaType = type,
        mimeType = null,
        fileName = null,
        sizeBytes = null,
        width = null,
        height = null,
        durationMs = duration,
        dateEpochSec = 1_700_000_000L,
        previewFileId = null,
        originalFileId = null,
    )

    private fun render(repo: CloudRepository, onOpen: (CloudMedia) -> Unit = {}) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                CloudScreen(viewModel = CloudViewModel(repo), onOpenMedia = onOpen)
            }
        }
    }

    @Test
    fun `ready state shows the grid with video and gif indicators`() {
        render(
            FakeCloudRepository(
                status = CloudStatus.Ready,
                title = "Telepic Backup",
                mediaList = listOf(
                    item(1, CloudMediaType.IMAGE),
                    item(2, CloudMediaType.VIDEO, duration = 24_000L),
                    item(3, CloudMediaType.GIF),
                ),
            ),
        )
        composeRule.onNodeWithText("Telepic Backup").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText("0:24").assertIsDisplayed()
        composeRule.onNodeWithText("GIF").assertIsDisplayed()
    }

    @Test
    fun `empty shows the honest empty state`() {
        render(FakeCloudRepository(CloudStatus.Empty, "Telepic Backup", emptyList()))
        composeRule.onNodeWithText("Your cloud is empty").assertIsDisplayed()
    }

    @Test
    fun `offline is shown distinctly from empty`() {
        render(FakeCloudRepository(CloudStatus.Offline, "Telepic Backup", emptyList()))
        composeRule.onNodeWithText("Telegram is unavailable").assertIsDisplayed()
    }

    @Test
    fun `not authenticated shows a connection guidance state`() {
        render(FakeCloudRepository(CloudStatus.NotAuthenticated, null, emptyList()))
        composeRule.onNodeWithText("Telegram is not connected").assertIsDisplayed()
    }

    @Test
    fun `failure surfaces a retryable error`() {
        render(FakeCloudRepository(CloudStatus.Failed("boom"), "Telepic Backup", emptyList()))
        composeRule.onNodeWithText("Couldn't load your cloud").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun `tapping a cloud tile opens it by its remote identity`() {
        var opened: CloudMedia? = null
        render(
            FakeCloudRepository(CloudStatus.Ready, "Telepic Backup", listOf(item(7, CloudMediaType.IMAGE))),
            onOpen = { opened = it },
        )
        composeRule.onNodeWithContentDescription("Cloud photo").performClick()
        org.junit.Assert.assertEquals(100L, opened?.chatId)
        org.junit.Assert.assertEquals(7L, opened?.messageId)
    }
}
