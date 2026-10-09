package com.telepix.ui.screens.viewer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.media.LocalMediaLookup
import com.telepix.data.media.NeighborDirection
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.domain.cloud.TelepixCloudDestination
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.navigation.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Viewer resolution by *typed source*: a local id via the lookup, a cloud (chatId, messageId) via the
 * manifest — each strictly in its own identity space, with missing → MISSING (never a substitute).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerViewModelTest {

    private fun local(id: Long, type: MediaType = MediaType.PHOTO) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = type, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1_000L, durationMillis = null, width = 1, height = 1,
        sizeBytes = 1L, bucketId = null, bucketName = null, relativePath = null,
    )

    private class FakeLookup(
        private val items: Map<Long, LocalMedia>,
        private val newer: Map<Long, Long?> = emptyMap(),
        private val older: Map<Long, Long?> = emptyMap(),
    ) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection): Long? =
            if (direction == NeighborDirection.NEWER) newer[id] else older[id]
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.toList().mapNotNull { items[it] }
    }

    private class FakeCloud(private val manifest: List<CloudMedia>) : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(manifest)
        override val destinationTitle: StateFlow<String?> = MutableStateFlow<String?>("Telepix Backup").asStateFlow()
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepixCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = CloudPreview("/tmp/prev_${media.messageId}", null, null)
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(request: com.telepix.domain.cloud.CloudUploadRequest, onProgress: (com.telepix.domain.cloud.CloudUploadProgress) -> Unit) = null
    }

    private fun cloudItem(messageId: Long) = CloudMedia(
        messageId = messageId, chatId = 100L, mediaType = CloudMediaType.IMAGE, mimeType = "image/jpeg",
        fileName = null, sizeBytes = null, width = null, height = null, durationMs = null,
        dateEpochSec = 2L, previewFileId = 9, originalFileId = 9,
    )

    @Test
    fun `resolves a local photo with neighbors and no download`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5)), newer = mapOf(5L to 6L), older = mapOf(5L to 4L)),
            cloudRepository = FakeCloud(emptyList()),
        )
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.READY, state.status)
        assertEquals(ViewerKind.PHOTO, state.kind)
        assertEquals(MediaSource.Local(6), state.previous)
        assertEquals(MediaSource.Local(4), state.next)
        assertTrue(state.item is ViewerItem.Local)
    }

    @Test
    fun `a missing local item is MISSING, not another item`() = runBlocking {
        val vm = ViewerViewModel(MediaSource.Local(99), FakeLookup(emptyMap()), FakeCloud(emptyList()))
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.MISSING, state.status)
        assertNull(state.item)
    }

    @Test
    fun `a cloud item resolves by remote identity with a preview`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Cloud(100, 7),
            localLookup = FakeLookup(emptyMap()),
            cloudRepository = FakeCloud(listOf(cloudItem(6), cloudItem(7), cloudItem(8))),
        )
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.READY, state.status)
        val cloud = state.item as ViewerItem.Cloud
        assertEquals(7L, cloud.media.messageId)
        assertEquals("/tmp/prev_7", cloud.previewPath)
        // cloud neighbors keep the same chat id and advance the message id
        assertEquals(MediaSource.Cloud(100, 6), state.previous)
        assertEquals(MediaSource.Cloud(100, 8), state.next)
    }

    @Test
    fun `a cloud original downloads only on request and reports unavailable honestly`() = runBlocking {
        val vm = ViewerViewModel(
            MediaSource.Cloud(100, 7),
            FakeLookup(emptyMap()),
            FakeCloud(listOf(cloudItem(7))),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        // No download until asked.
        assertEquals(CloudDownload.Idle, vm.uiState.value.download)
        vm.downloadOriginal()
        val after = vm.uiState.first { it.download != CloudDownload.Idle }
        // Defensive TDLib returns null → honest Unavailable, never a fabricated success.
        assertEquals(CloudDownload.Unavailable, after.download)
    }

    @Test
    fun `a cloud item absent from the manifest is MISSING`() = runBlocking {
        val vm = ViewerViewModel(MediaSource.Cloud(100, 999), FakeLookup(emptyMap()), FakeCloud(listOf(cloudItem(7))))
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.MISSING, state.status)
    }
}
