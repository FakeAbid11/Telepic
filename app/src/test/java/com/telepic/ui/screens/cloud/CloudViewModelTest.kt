package com.telepic.ui.screens.cloud

import android.os.Looper
import com.telepic.data.cloud.CloudRepository
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudPreviewState
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.TelepicCloudDestination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Guards the Cloud preview lifecycle (P2): a preview is explicitly Loading / Loaded / Failed rather
 * than an indistinguishable permanent placeholder, a failed fetch offers a retry, and an item with no
 * preview file is honestly non-retryable.
 *
 * Robolectric so [androidx.lifecycle.viewModelScope]'s Main dispatcher runs on the idling test looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudViewModelTest {

    private class FakeCloudRepository(
        private val previewPaths: Map<Long, String> = emptyMap(),
    ) : CloudRepository {
        var previewCalls = 0
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val destinationTitle: StateFlow<String?> = MutableStateFlow("Telepic Backup").asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(emptyList())
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? {
            previewCalls++
            val path = previewPaths[media.messageId] ?: return null
            return CloudPreview(path, null, null)
        }
        override suspend fun downloadOriginal(media: CloudMedia): com.telepic.domain.cloud.LocalDownloadedMedia? = null
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

    private fun item(id: Long, previewFileId: Int? = 1) = CloudMedia(
        messageId = id,
        chatId = 100L,
        mediaType = CloudMediaType.IMAGE,
        mimeType = null,
        fileName = null,
        sizeBytes = null,
        width = null,
        height = null,
        durationMs = null,
        dateEpochSec = 1_700_000_000L,
        previewFileId = previewFileId,
        originalFileId = null,
    )

    private fun settle() = repeat(6) { shadowOf(Looper.getMainLooper()).idle() }

    private lateinit var repo: FakeCloudRepository
    private lateinit var vm: CloudViewModel

    @Before
    fun setup() {
        repo = FakeCloudRepository(previewPaths = mapOf(1L to "/tmp/p1"))
        vm = CloudViewModel(repo)
    }

    @Test
    fun `successful preview resolves to Loaded`() {
        vm.loadPreview(item(1))
        settle()
        assertEquals(CloudPreviewState.Loaded("/tmp/p1"), vm.previews.value[100L to 1L])
    }

    @Test
    fun `a null preview for an item with a file id is a retryable failure`() {
        vm.loadPreview(item(2, previewFileId = 1)) // repo has no path for id 2 → getPreview null
        settle()
        assertEquals(CloudPreviewState.Failed(retryable = true), vm.previews.value[100L to 2L])
    }

    @Test
    fun `an item with no preview file is a non-retryable failure and is never fetched`() {
        vm.loadPreview(item(3, previewFileId = null))
        settle()
        assertEquals(CloudPreviewState.Failed(retryable = false), vm.previews.value[100L to 3L])
        assertEquals(0, repo.previewCalls)
    }

    @Test
    fun `load is idempotent — a settled item is not re-fetched`() {
        vm.loadPreview(item(1))
        settle()
        val callsAfterFirst = repo.previewCalls
        vm.loadPreview(item(1))
        settle()
        assertEquals(callsAfterFirst, repo.previewCalls)
    }

    @Test
    fun `retry re-fetches and can recover to Loaded`() {
        // id 2 initially fails (no path). Retry after teaching the repo its path.
        vm.loadPreview(item(2, previewFileId = 1))
        settle()
        assertTrue(vm.previews.value[100L to 2L] is CloudPreviewState.Failed)
        val recovering = CloudViewModel(FakeCloudRepository(previewPaths = mapOf(2L to "/tmp/p2")))
        recovering.loadPreview(item(2))
        settle()
        assertEquals(CloudPreviewState.Loaded("/tmp/p2"), recovering.previews.value[100L to 2L])
    }

    @Test
    fun `refresh clears cached preview states`() {
        vm.loadPreview(item(1))
        settle()
        assertTrue(vm.previews.value.isNotEmpty())
        vm.refresh()
        settle()
        assertTrue(vm.previews.value.isEmpty())
    }
}
