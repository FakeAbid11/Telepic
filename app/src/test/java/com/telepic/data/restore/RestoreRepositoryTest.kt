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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The restore pipeline (download → verify → publish) against a fake cloud source and a fake
 * publisher. Real Telegram download + the Android MediaStore write are device-gated; these verify the
 * state mapping and the no-false-success guarantees, NOT a live end-to-end restore.
 *
 * Robolectric because the ledger-failure path goes through the app's logging seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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
        override suspend fun confirmDestination(): TelepicCloudDestination? = null
        override suspend fun createNewDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? =
            downloadedPath?.let { LocalDownloadedMedia(it, media.chatId, media.messageId) }
        override suspend fun uploadMedia(
            request: CloudUploadRequest,
            onProgress: (CloudUploadProgress) -> Unit,
            onSent: suspend (Long, Long) -> Unit,
        ): CloudUploadResult? = null
        override suspend fun confirmUpload(
            chatId: Long,
            messageId: Long,
            mediaType: com.telepic.domain.cloud.CloudMediaType,
            contentHash: String?,
            contentSizeBytes: Long?,
        ): CloudUploadResult = throw NotImplementedError()
    }

    private class FakePublisher(private var result: PublishResult) : MediaStorePublisher {
        var lastSource: String? = null

        /** How many times bytes were actually written — the duplicate-suppression assertion. */
        var publishCount = 0

        /** The capture time the last publish was given, so the date contract can be asserted. */
        var lastDateTakenMillis: Long? = null

        /** URIs the fake provider currently holds a live row for. */
        val liveUris = mutableSetOf<String>()

        override suspend fun publish(
            sourcePath: String,
            mimeType: String?,
            displayName: String?,
            dateTakenMillis: Long?,
        ): PublishResult {
            lastSource = sourcePath
            lastDateTakenMillis = dateTakenMillis
            publishCount++
            (result as? PublishResult.Inserted)?.let { liveUris += it.uri }
            return result
        }

        override suspend fun exists(uri: String): Boolean = uri in liveUris

        fun setResult(r: PublishResult) { result = r }

        /** Simulates the user deleting the published copy from the gallery. */
        fun delete(uri: String) { liveUris.remove(uri) }
    }

    private class FakeLedger : RestoreLedger {
        private val map = mutableMapOf<String, String>()
        override suspend fun publishedUri(identity: RestoreIdentity): String? = map[identity.token()]
        override suspend fun record(identity: RestoreIdentity, uri: String) { map[identity.token()] = uri }
        override suspend fun forget(identity: RestoreIdentity) { map.remove(identity.token()) }
    }

    /** A ledger that cannot write after the file has already been published. */
    private class ThrowingLedger : RestoreLedger {
        override suspend fun publishedUri(identity: RestoreIdentity): String? = null
        override suspend fun record(identity: RestoreIdentity, uri: String) {
            error("ledger storage is unavailable")
        }
        override suspend fun forget(identity: RestoreIdentity) = Unit
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
    fun `a failed ledger write does not turn a published restore into a failure`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/99"))
        val repository = DefaultRestoreRepository(
            cloudRepository = FakeCloud(file.absolutePath),
            publisher = publisher,
            ledger = ThrowingLedger(),
        )

        val result = repository.restore(media(hash = sha(bytes)))

        assertTrue("the file is in the gallery, so the save is real", result is RestoreResult.Restored)
        assertEquals("content://media/99", (result as RestoreResult.Restored).localUri)
        assertEquals(1, publisher.publishCount)
    }

    @Test
    fun `the original capture time travels with the published row`() = runBlocking {
        // Without the capture time the provider stamps the row with the *restore* time, so a 2019
        // photo restored today landed at the end of the timeline — a silent corruption of the
        // library's chronology.
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/99"))
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), publisher)
        repository.restore(media(hash = sha(bytes)))
        // The fixture's item carries dateEpochSec = 1, i.e. 1000 ms.
        assertEquals(1_000L, publisher.lastDateTakenMillis)
    }

    @Test
    fun `a null download is an honest unavailable, never a false success`() = runBlocking {        val repository = DefaultRestoreRepository(FakeCloud(null), FakePublisher(PublishResult.Inserted("x")))
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

    @Test
    fun `a repeat restore of the same item reuses the recorded copy without republishing`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/7"))
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), publisher, FakeLedger())

        val first = repository.restore(media())
        val second = repository.restore(media())

        assertEquals("content://media/7", (first as RestoreResult.Restored).localUri)
        assertEquals("content://media/7", (second as RestoreResult.Restored).localUri)
        assertEquals("the same item must not be published twice", 1, publisher.publishCount)
    }

    @Test
    fun `a different cloud item is published even when it shares name, size and mime`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/x"))
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), publisher, FakeLedger())

        // Two DIFFERENT remote messages carrying byte-identical files: identity must be the remote
        // id, not the name/size/mime — which would (wrongly) collapse them into one.
        repository.restore(media().copy(messageId = 7L))
        repository.restore(media().copy(messageId = 8L))

        assertEquals(2, publisher.publishCount)
    }

    @Test
    fun `a recorded copy the user deleted is forgotten and republished, never falsely reported`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/9"))
        val ledger = FakeLedger()
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), publisher, ledger)

        assertTrue(repository.restore(media()) is RestoreResult.Restored)
        publisher.delete("content://media/9") // the user removed it from the gallery

        val again = repository.restore(media())
        assertTrue(again is RestoreResult.Restored)
        assertEquals("the vanished copy must be republished, not trusted", 2, publisher.publishCount)
    }

    @Test
    fun `bytes that cannot be read fail integrity, never a false success`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val repository = DefaultRestoreRepository(
            cloudRepository = FakeCloud(file.absolutePath),
            publisher = FakePublisher(PublishResult.Inserted("x")),
            ledger = null,
            computeRestoredHash = { throw java.io.IOException("unreadable") },
        )
        assertEquals(RestoreResult.Failed(RestoreFailure.VERIFY_FAILED), repository.restore(media()))
    }

    @Test
    fun `concurrent restores of the same item publish exactly one copy`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = fileWithBytes(bytes)
        val publisher = FakePublisher(PublishResult.Inserted("content://media/10"))
        val repository = DefaultRestoreRepository(FakeCloud(file.absolutePath), publisher, FakeLedger())

        val results = (1..8).map {
            async(Dispatchers.Default) { repository.restore(media()) }
        }.awaitAll()

        assertTrue(results.all { it is RestoreResult.Restored })
        assertEquals(1, publisher.publishCount)
    }
}
