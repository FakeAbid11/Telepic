package com.telepix.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepix.data.cloud.CloudNetworkException
import com.telepix.data.cloud.CloudRepository
import com.telepix.data.cloud.CloudUploadRejectedException
import com.telepix.data.cloud.db.TelepixDatabase
import com.telepix.domain.backup.BackupState
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudStatus
import com.telepix.domain.cloud.CloudUploadRequest
import com.telepix.domain.cloud.CloudUploadResult
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.domain.cloud.TelepixCloudDestination
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import com.telepix.telegram.TelegramAuthState
import com.telepix.telegram.TelegramUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup engine driven end-to-end against in-memory Room + a fake [CloudRepository]. Proves the
 * core invariant with no Telegram: BACKED_UP happens only when the fake returns a real remote
 * identity for the destination chat; transient/permanent/auth/network cases land in the right states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultBackupRepositoryTest {

    private lateinit var db: TelepixDatabase
    private val authorized = MutableStateFlow<TelegramAuthState>(
        TelegramAuthState.Authorized(TelegramUser(1, "A", "B", null)),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private class FakeCloud(
        var destination: TelepixCloudDestination? = TelepixCloudDestination(100L, "Telepix Backup", true, true, true),
        var uploadResult: CloudUploadResult? = CloudUploadResult(100L, 500L, 7, CloudMediaType.IMAGE),
        var uploadError: Throwable? = null,
    ) : CloudRepository {
        override val status = MutableStateFlow<CloudStatus>(CloudStatus.Ready)
        override val media = kotlinx.coroutines.flow.flowOf<List<CloudMedia>>(emptyList())
        override val destinationTitle = MutableStateFlow<String?>("Telepix Backup")
        var uploadCalls = 0

        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination() = destination
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(
            request: CloudUploadRequest,
            onProgress: (com.telepix.domain.cloud.CloudUploadProgress) -> Unit,
        ): CloudUploadResult? {
            uploadCalls++
            uploadError?.let { throw it }
            return uploadResult
        }
    }

    private fun repo(cloud: CloudRepository, auth: MutableStateFlow<TelegramAuthState> = authorized) =
        DefaultBackupRepository(
            dao = db.backupQueueDao(),
            cloudRepository = cloud,
            authState = auth,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun media(id: Long) = LocalMedia(
        id = id,
        contentUri = Uri.parse("content://media/external/images/$id"),
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        displayName = "p$id.jpg",
        dateMillis = 1_700_000_000_000L,
        durationMillis = null,
        width = 100,
        height = 100,
        sizeBytes = 1000L,
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/",
    )

    @Test
    fun `enqueue is idempotent per local media id`() = runBlocking {
        val r = repo(FakeCloud())
        assertTrue(r.enqueue(media(1)))
        assertEquals(false, r.enqueue(media(1)))
        assertEquals(true, r.enqueue(media(2)))
        assertEquals(2, db.backupQueueDao().observeAll().first().size)
    }

    @Test
    fun `a successful upload persists remote identity and marks BACKED_UP`() = runBlocking {
        val cloud = FakeCloud()
        val r = repo(cloud)
        r.enqueue(media(1))
        val summary = r.processPendingWork(maxItems = 1)
        assertEquals(1, summary.uploaded)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(100L, row.telegramChatId)
        assertEquals(500L, row.telegramMessageId)
    }

    @Test
    fun `never marks BACKED_UP when Telegram does not confirm`() = runBlocking {
        val cloud = FakeCloud(uploadResult = null)
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        val row = db.backupQueueDao().observeAll().first().first()
        assertTrue(row.state != BackupState.BACKED_UP.name)
    }

    @Test
    fun `transient network failure waits for network and consumes a retry`() = runBlocking {
        val cloud = FakeCloud(uploadError = CloudNetworkException())
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, row.state)
        assertEquals(1, row.retryCount)
    }

    @Test
    fun `permanent rejection fails without retry`() = runBlocking {
        val cloud = FakeCloud(uploadError = CloudUploadRejectedException("Unsupported media"))
        val r = repo(cloud)
        r.enqueue(media(1))
        val summary = r.processPendingWork(maxItems = 1)
        assertEquals(1, summary.failed)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.FAILED.name, row.state)
        assertEquals(0, row.retryCount)
    }

    @Test
    fun `unauthenticated work parks in WAITING_FOR_AUTH without uploading`() = runBlocking {
        val cloud = FakeCloud()
        val r = repo(cloud, auth = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected))
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.WAITING_FOR_AUTH.name, row.state)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun `missing destination parks the item rather than failing it`() = runBlocking {
        val cloud = FakeCloud().apply { destination = null }
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, row.state)
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun `retry requeues a failed item`() = runBlocking {
        val cloud = FakeCloud(uploadError = CloudUploadRejectedException("nope"))
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        val id = db.backupQueueDao().observeAll().first().first().id
        r.retry(id)
        assertEquals(BackupState.QUEUED.name, db.backupQueueDao().observeAll().first().first().state)
    }

    @Test
    fun `cancel marks an item CANCELLED`() = runBlocking {
        val r = repo(FakeCloud())
        r.enqueue(media(1))
        val id = db.backupQueueDao().observeAll().first().first().id
        r.cancel(id)
        assertEquals(BackupState.CANCELLED.name, db.backupQueueDao().observeAll().first().first().state)
    }

    @Test
    fun `recovery returns an interrupted upload with no identity to QUEUED`() = runBlocking {
        val dao = db.backupQueueDao()
        val r = repo(FakeCloud())
        r.enqueue(media(1))
        val row = dao.observeAll().first().first()
        dao.markUploading(row.id, System.currentTimeMillis())
        // Simulate a crash mid-upload (no remote id persisted).
        r.recoverInterruptedWork()
        assertEquals(BackupState.QUEUED.name, dao.observeAll().first().first().state)
    }

    @Test
    fun `exhausting retries stops picking up an item`() = runBlocking {
        val cloud = FakeCloud(uploadError = CloudNetworkException())
        val r = repo(cloud)
        r.enqueue(media(1))
        repeat(5) { r.processPendingWork(maxItems = 1) }
        val summary = r.processPendingWork(maxItems = 1)
        assertEquals(0, summary.processed)
    }

    @Test
    fun `a single transient failure consumes only one retry per run regardless of batch size`() = runBlocking {
        val cloud = FakeCloud(uploadError = CloudNetworkException())
        val r = repo(cloud)
        r.enqueue(media(1))
        // Old behaviour re-selected the same WAITING_FOR_NETWORK item until its whole budget was gone.
        val summary = r.processPendingWork(maxItems = 5)
        assertEquals(1, summary.processed)
        assertEquals(1, summary.waiting)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(1, row.retryCount)
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, row.state)
    }

    @Test
    fun `manual retry revives a retry-exhausted item`() = runBlocking {
        val cloud = FakeCloud(uploadError = CloudNetworkException())
        val r = repo(cloud)
        r.enqueue(media(1))
        val id = db.backupQueueDao().observeAll().first().first().id
        repeat(5) { r.processPendingWork(maxItems = 1) } // exhaust the automatic budget
        assertEquals(0, r.processPendingWork(maxItems = 1).processed) // no longer actionable

        r.retry(id) // explicit user retry
        val after = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.QUEUED.name, after.state)
        assertEquals(0, after.retryCount)
        // ...and it is actionable again.
        assertEquals(1, r.processPendingWork(maxItems = 1).processed)
    }
}
