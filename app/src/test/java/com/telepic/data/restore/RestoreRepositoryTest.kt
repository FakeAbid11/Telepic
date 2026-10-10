package com.telepic.data.restore

import com.telepic.data.backup.hash.ContentHashing
import com.telepic.data.cloud.CloudRepository
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUploadProgress
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore pipeline (download → verify → publish) against a fake cloud source and a fake
 * publisher. Real Telegram download + the Android MediaStore write are device-gated; these verify the
 * state mapping and the no-false-success guarantees, NOT a live end-to-end restore.
 */
class RestoreRepositoryTest {

    private fun fileWithBytes(bytes: ByteArray): File =
        File.createTempFile("telepic_restore", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    private suspend fun sha(bytes: ByteArray): String =
        bytes.inputStream().use { ContentHashing.sha256(it).sha256 }

    private fun media(hash: String? = null) = CloudMedia(
        messageId = 7L, chatId = 100L, mediaType = CloudMediaType.IMAGE, mimeType = "image/jpeg",
        fileName = "photo.jpg", sizeBytes = 4L, width = 1, height = 1, durationMs = null,
        dateEpochSec = 1L, previewFileId = null, originalFileId = null, contentHash = hash,
    )

    private class FakeCloud(private val downloadedPath: String?) : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val destinationTitle: StateFlow<String?> = MutableStateFlow<String?>("Telepic Backup").asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(emptyList())
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? =
            downloadedPath?.let { LocalDownloadedMedia(it, media.chatId, media.messageId) }
        override suspend fun uploadMedia(
            request: CloudUploadRequest,
            onProgress: (CloudUploadProgress) -> Unit,
        ): CloudUploadResult? = null
    }

    private class FakePublisher(private var result: PublishResult) : MediaStorePublisher {
        var lastSource: String? = null
        override suspend fun publish(sourcePath: String, mimeType: String?, displayName: String?): PublishResult {
            lastSource = sourcePath
            return result
        }
        fun setResult(r: PublishResult) { result = r }
    }

    @Test
    fun `successful download and publish yields a restored uri`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), FakePublisher(PublishResult.Inserted("content://media/99")))
        val result = repository.restore(media(hash = sha(bytes)))
        assertTrue(result is RestoreResult.Restored)
        assertEquals("content://media/99", (result as RestoreResult.Restored).localUri)
    }

    @Test
    fun `a null download is an honest unavailable, never a false success`() = runBlocking {
        val repository = DefaultRestoreRepository(FakeCloud(null), FakePublisher(PublishResult.Inserted("x")))
        val result = repository.restore(media())
        assertEquals(RestoreResult.Failed(RestoreFailure.DOWNLOAD_UNAVAILABLE), result)
    }

    @Test
    fun `a missing or empty staged file fails verification of presence`() = runBlocking {
        val repository = DefaultRestoreRepository(FakeCloud("/no/such/file"), FakePublisher(PublishResult.Inserted("x")))
        assertEquals(RestoreResult.Failed(RestoreFailure.MISSING_REMOTE), repository.restore(media()))
    }

    @Test
    fun `a differing original-file hash does not block a legitimate restore (lossy representation)`() = runBlocking {
        val bytes = byteArrayOf(9, 9, 9, 9)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/1"))
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), publisher)
        // The manifest carries the ORIGINAL file's hash, which differs from Telegram's stored photo
        // bytes; restore must still succeed and report the restored bytes' hash for the record.
        val result = repository.restore(media(hash = sha(byteArrayOf(1, 2, 3, 4))))
        assertTrue(result is RestoreResult.Restored)
        assertEquals(sha(bytes), (result as RestoreResult.Restored).restoredContentHash)
    }

    @Test
    fun `a present non-empty download reports the restored bytes hash`() = runBlocking {
        val bytes = byteArrayOf(5, 6, 7, 8)
        val file = fileWithBytes(bytes)
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), FakePublisher(PublishResult.Inserted("content://media/2")))
        val result = repository.restore(media())
        assertEquals(sha(bytes), (result as RestoreResult.Restored).restoredContentHash)
    }

    @Test
    fun `low storage during publish is reported distinctly`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), FakePublisher(PublishResult.NoSpace))
        assertEquals(RestoreResult.Failed(RestoreFailure.LOW_STORAGE), repository.restore(media(hash = sha(bytes))))
    }

    @Test
    fun `a publish failure is not reported as restored`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), FakePublisher(PublishResult.Failed))
        assertEquals(RestoreResult.Failed(RestoreFailure.PUBLISH_FAILED), repository.restore(media(hash = sha(bytes))))
    }
}
