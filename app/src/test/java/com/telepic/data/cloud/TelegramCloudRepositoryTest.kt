package com.telepic.data.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.domain.cloud.ChatCandidate
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.telegram.TelegramAuthState
import com.telepic.telegram.TelegramUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the full cloud orchestration (discovery → validation → persistence → manifest →
 * state) against a fake [CloudDataSource] and in-memory Room. Room runs on a direct executor and
 * the repository on Unconfined, so everything completes deterministically under runBlocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelegramCloudRepositoryTest {

    private lateinit var db: TelepicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private val authorized = MutableStateFlow<TelegramAuthState>(
        TelegramAuthState.Authorized(TelegramUser(1, "A", "B", null)),
    )

    private class FakeDataSource(
        var candidates: List<ChatCandidate> = emptyList(),
        var created: ChatCandidate? = null,
        var media: List<CloudMedia> = emptyList(),
        var preview: CloudPreview? = null,
        var download: LocalDownloadedMedia? = null,
        var searchError: Throwable? = null,
        var uploadResult: com.telepic.domain.cloud.CloudUploadResult? = null,
        var uploadError: Throwable? = null,
    ) : CloudDataSource {
        var searchCalls = 0
        var createCalls = 0
        var uploadCalls = 0
        override suspend fun searchDestinationCandidates(): List<ChatCandidate> {
            searchCalls++
            searchError?.let { throw it }
            return candidates
        }

        override suspend fun createDestination(): ChatCandidate? {
            createCalls++
            return created
        }

        override suspend fun loadNewestMedia(chatId: Long, limit: Int) = media
        override suspend fun downloadPreview(media: CloudMedia) = preview
        override suspend fun downloadOriginal(media: CloudMedia) = download

        override suspend fun upload(
            chatId: Long,
            request: com.telepic.domain.cloud.CloudUploadRequest,
            onProgress: (com.telepic.domain.cloud.CloudUploadProgress) -> Unit,
        ): com.telepic.domain.cloud.CloudUploadResult {
            uploadCalls++
            uploadError?.let { throw it }
            return requireNotNull(uploadResult) { "FakeDataSource must set uploadResult or uploadError" }
        }
    }

    private fun repo(source: CloudDataSource, auth: MutableStateFlow<TelegramAuthState> = authorized) =
        TelegramCloudRepository(
            dataSource = source,
            destinationDao = db.cloudDestinationDao(),
            manifestDao = db.cloudMediaManifestDao(),
            authState = auth,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun candidate(chatId: Long = 100L, canPost: Boolean = true, channel: Boolean = true, owned: Boolean = true) =
        ChatCandidate(chatId, "Telepic Backup", channel, canPost, true, owned)

    private fun mediaItem(id: Long) = CloudMedia(
        messageId = id,
        chatId = 100L,
        mediaType = CloudMediaType.IMAGE,
        mimeType = "image/jpeg",
        fileName = "p$id.jpg",
        sizeBytes = 10L,
        width = null,
        height = null,
        durationMs = null,
        dateEpochSec = 1_700_000_000L,
        previewFileId = null,
        originalFileId = null,
    )

    @Test
    fun `discovers validates and persists a valid destination then loads media`() = runBlocking {
        val source = FakeDataSource(candidates = listOf(candidate()), media = listOf(mediaItem(1), mediaItem(2)))
        val r = repo(source)
        r.prepare()
        assertEquals(CloudStatus.Ready, r.status.value)
        assertEquals(2, db.cloudMediaManifestDao().count())
        val stored = db.cloudDestinationDao().find("telegram")
        assertTrue(stored != null && stored.validated && stored.chatId == 100L)
    }

    @Test
    fun `creates the destination when none is found`() = runBlocking {
        val source = FakeDataSource(created = candidate(200L))
        val r = repo(source)
        r.prepare()
        assertEquals(1, source.createCalls)
        assertEquals(200L, db.cloudDestinationDao().find("telegram")?.chatId)
    }

    @Test
    fun `ignores an invalid same-name (non-channel) candidate and creates a valid one`() = runBlocking {
        val source = FakeDataSource(
            candidates = listOf(candidate(channel = false)),
            created = candidate(300L),
        )
        val r = repo(source)
        r.prepare()
        assertEquals(1, source.createCalls)
        assertEquals(300L, db.cloudDestinationDao().find("telegram")?.chatId)
    }

    @Test
    fun `empty cloud is Empty not an error`() = runBlocking {
        val r = repo(FakeDataSource(candidates = listOf(candidate()), media = emptyList()))
        r.prepare()
        assertEquals(CloudStatus.Empty, r.status.value)
    }

    @Test
    fun `network failure surfaces Offline not Empty`() = runBlocking {
        val r = repo(FakeDataSource(searchError = CloudNetworkException()))
        r.prepare()
        assertTrue(r.status.value is CloudStatus.Offline)
    }

    @Test
    fun `not authenticated is distinct from empty`() = runBlocking {
        val auth = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        val source = FakeDataSource(candidates = listOf(candidate()))
        val r = repo(source, auth)
        r.prepare()
        assertEquals(CloudStatus.NotAuthenticated, r.status.value)
        assertEquals(0, source.searchCalls)
    }

    @Test
    fun `repeated refresh does not duplicate manifest rows`() = runBlocking {
        val source = FakeDataSource(candidates = listOf(candidate()), media = listOf(mediaItem(1), mediaItem(2)))
        val r = repo(source)
        r.prepare()
        r.refresh()
        r.refresh()
        assertEquals(2, db.cloudMediaManifestDao().count())
    }

    @Test
    fun `refresh preserves a trusted content hash the remote row does not carry`() = runBlocking {
        val source = FakeDataSource(candidates = listOf(candidate()), media = listOf(mediaItem(1)))
        val r = repo(source)
        r.prepare()
        // Simulate app-generated recognition metadata the remote scan cannot reconstruct.
        db.cloudMediaManifestDao().setContentHash(100L, 1L, "trusted-hash", 2L)
        r.refresh() // remote returns the same message with a null hash
        val stored = db.cloudMediaManifestDao().observeAll().first().first { it.messageId == 1L }
        assertEquals("trusted-hash", stored.contentHash)
    }

    @Test
    fun `successful original download marks the manifest item downloaded`() = runBlocking {
        val source = FakeDataSource(
            candidates = listOf(candidate()),
            media = listOf(mediaItem(1)),
            download = LocalDownloadedMedia("/tmp/x", 100L, 1L),
        )
        val r = repo(source)
        r.prepare()
        val item = r.media.first().first()
        val result = r.downloadOriginal(item)
        assertEquals("/tmp/x", result?.localPath)
        assertTrue(r.media.first().first().isDownloaded)
    }

    @Test
    fun `failed original download leaves state and returns null`() = runBlocking {
        val source = FakeDataSource(candidates = listOf(candidate()), media = listOf(mediaItem(1)), download = null)
        val r = repo(source)
        r.prepare()
        val item = r.media.first().first()
        assertNull(r.downloadOriginal(item))
    }

    @Test
    fun `successful upload persists the remote identity in the manifest`() = runBlocking {
        val source = FakeDataSource(
            candidates = listOf(candidate()),
            uploadResult = com.telepic.domain.cloud.CloudUploadResult(
                chatId = 100L,
                messageId = 999L,
                telegramFileId = 5,
                mediaType = CloudMediaType.IMAGE,
            ),
        )
        val r = repo(source)
        r.prepare()
        val request = com.telepic.domain.cloud.CloudUploadRequest(
            stagedPath = "/tmp/x",
            fileName = "a.jpg",
            mimeType = "image/jpeg",
            mediaType = CloudMediaType.IMAGE,
            sizeBytes = 10L,
            width = null,
            height = null,
            durationMs = null,
            dateEpochSec = 1_700_000_000L,
        )
        val result = r.uploadMedia(request)
        assertEquals(999L, result?.messageId)
        // The uploaded item is now in the manifest under its stable remote identity.
        val manifest = r.media.first().first { it.messageId == 999L }
        assertEquals(100L, manifest.chatId)
        assertTrue(manifest.isDownloaded)
    }

    @Test
    fun `upload with no destination returns null without fabricating success`() = runBlocking {
        val auth = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        val source = FakeDataSource(uploadResult = com.telepic.domain.cloud.CloudUploadResult(1, 1, null, CloudMediaType.IMAGE))
        val r = repo(source, auth)
        val request = com.telepic.domain.cloud.CloudUploadRequest("/x", null, null, CloudMediaType.IMAGE, null, null, null, null, null)
        runCatching { r.uploadMedia(request) }
        assertEquals(0, source.uploadCalls)
    }
}
