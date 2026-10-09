package com.telepix.ui.screens.viewer

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.media.LocalMediaLookup
import com.telepix.data.media.NeighborDirection
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.domain.cloud.TelepixCloudDestination
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.navigation.MediaSource
import com.telepix.ui.theme.TelepixTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric Compose coverage of the Viewer chrome and states (photo path; video is device-only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ViewerUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun localPhoto(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1_700_000_000_000L, durationMillis = null, width = 10, height = 10,
        sizeBytes = 10L, bucketId = null, bucketName = null, relativePath = null,
    )

    private class Lookup(private val items: Map<Long, LocalMedia>, private val older: Long? = null) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection): Long? =
            if (direction == NeighborDirection.OLDER) older else null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.toList().mapNotNull { items[it] }
    }

    private object NoCloud : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(emptyList())
        override val destinationTitle: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepixCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(request: com.telepix.domain.cloud.CloudUploadRequest, onProgress: (com.telepix.domain.cloud.CloudUploadProgress) -> Unit) = null
    }

    @Test
    fun `a local photo shows the source label and a working back action`() {
        var backed = false
        val vm = ViewerViewModel(MediaSource.Local(5), Lookup(mapOf(5L to localPhoto(5), 9L to localPhoto(9)), older = 9L), NoCloud)
        composeRule.setContent { TelepixTheme { ViewerScreen(vm, onBack = { backed = true }) } }

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("On this device").assertIsDisplayed()
        // A next item exists → the forward control is present and back returns to the origin.
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }

    @Test
    fun `a missing item shows an honest empty state`() {
        val vm = ViewerViewModel(MediaSource.Local(404), Lookup(emptyMap()), NoCloud)
        composeRule.setContent { TelepixTheme { ViewerScreen(vm, onBack = {}) } }
        composeRule.onNodeWithText("This media is no longer available").assertIsDisplayed()
    }
}
