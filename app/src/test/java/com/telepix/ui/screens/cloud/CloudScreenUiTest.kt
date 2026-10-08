package com.telepix.ui.screens.cloud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.telepix.data.cloud.CloudRepository
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.domain.cloud.TelepixCloudDestination
import com.telepix.ui.theme.TelepixTheme
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
        override suspend fun ensureDestination(): TelepixCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
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

    private fun render(repo: CloudRepository) {
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                CloudScreen(viewModel = CloudViewModel(repo))
            }
        }
    }

    @Test
    fun `ready state shows the grid with video and gif indicators`() {
        render(
            FakeCloudRepository(
                status = CloudStatus.Ready,
                title = "Telepix Backup",
                mediaList = listOf(
                    item(1, CloudMediaType.IMAGE),
                    item(2, CloudMediaType.VIDEO, duration = 24_000L),
                    item(3, CloudMediaType.GIF),
                ),
            ),
        )
        composeRule.onNodeWithText("Telepix Backup").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText("0:24").assertIsDisplayed()
        composeRule.onNodeWithText("GIF").assertIsDisplayed()
    }

    @Test
    fun `empty shows the honest empty state`() {
        render(FakeCloudRepository(CloudStatus.Empty, "Telepix Backup", emptyList()))
        composeRule.onNodeWithText("Your cloud is empty").assertIsDisplayed()
    }

    @Test
    fun `offline is shown distinctly from empty`() {
        render(FakeCloudRepository(CloudStatus.Offline, "Telepix Backup", emptyList()))
        composeRule.onNodeWithText("Telegram is unavailable").assertIsDisplayed()
    }

    @Test
    fun `not authenticated shows a connection guidance state`() {
        render(FakeCloudRepository(CloudStatus.NotAuthenticated, null, emptyList()))
        composeRule.onNodeWithText("Telegram is not connected").assertIsDisplayed()
    }

    @Test
    fun `failure surfaces a retryable error`() {
        render(FakeCloudRepository(CloudStatus.Failed("boom"), "Telepix Backup", emptyList()))
        composeRule.onNodeWithText("Couldn't load your cloud").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }
}
