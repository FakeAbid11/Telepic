package com.telepic.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.CloudNetworkException
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.cloud.CloudUploadRejectedException
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.domain.backup.BackupState
import com.telepic.domain.backup.RemoteMediaIdentity
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
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
 * The backup engine driven end-to-end against in-memory Room + a fake [CloudRepository]. Proves the
 * core invariant with no Telegram: BACKED_UP happens only when the fake returns a real remote
 * identity for the destination chat; transient/permanent/auth/network cases land in the right states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultBackupRepositoryTest {

    private lateinit var db: TelepicDatabase
    private val authorized = MutableStateFlow<TelegramAuthState>(
        TelegramAuthState.Authorized(TelegramUser(1, "A", "B", null)),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        stagingDir.deleteRecursively()
    }

    private class FakeCloud(
        var destination: TelepicCloudDestination? = TelepicCloudDestination(100L, "Telepic Backup", true, true, true),
        var uploadResult: CloudUploadResult? = CloudUploadResult(100L, 500L, 7, CloudMediaType.IMAGE),
        var uploadError: Throwable? = null,
        // When set, the fake reports TDLib accepting the send (fires onSent) BEFORE it returns or
        // throws — exactly the real data source's behavior for the pending-identity guarantee.
        var sentIdentity: Pair<Long, Long>? = null,
        var confirmResult: CloudUploadResult? = null,
        var confirmError: Throwable? = null,
        /**
         * Runs inside [uploadMedia] just before it returns, so a test can mutate the queue while an
         * upload is "in flight" — the mid-flight user cancel the repository has to report honestly.
         */
        var beforeUploadReturn: (suspend () -> Unit)? = null,
        /** The manifest match the pre-send backstop should find, when a test wants one. */
        var remoteByContent: RemoteMediaIdentity? = null,
    ) : CloudRepository {
        override val status = MutableStateFlow<CloudStatus>(CloudStatus.Ready)
        override val media = kotlinx.coroutines.flow.flowOf<List<CloudMedia>>(emptyList())
        override val destinationTitle = MutableStateFlow<String?>("Telepic Backup")
        var uploadCalls = 0
        var confirmCalls = 0
        var findRemoteCalls = 0

        /** The most recent upload request, so a test can assert what the engine actually asked for. */
        var lastRequest: CloudUploadRequest? = null

        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination() = destination
        override suspend fun findRemoteByContent(contentHash: String, sizeBytes: Long): RemoteMediaIdentity? {
            findRemoteCalls++
            return remoteByContent
        }
        override suspend fun confirmDestination(): TelepicCloudDestination? = null
        override suspend fun createNewDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(
            request: CloudUploadRequest,
            onProgress: (com.telepic.domain.cloud.CloudUploadProgress) -> Unit,
            onSent: suspend (chatId: Long, messageId: Long) -> Unit,
        ): CloudUploadResult? {
            uploadCalls++
            lastRequest = request
            sentIdentity?.let { onSent(it.first, it.second) }
            uploadError?.let { throw it }
            beforeUploadReturn?.invoke()
            return uploadResult
        }
        override suspend fun confirmUpload(
            chatId: Long,
            messageId: Long,
            mediaType: CloudMediaType,
            contentHash: String?,
            contentSizeBytes: Long?,
        ): CloudUploadResult {
            confirmCalls++
            confirmError?.let { throw it }
            return requireNotNull(confirmResult) { "FakeCloud must set confirmResult or confirmError" }
        }
    }

    /**
     * A staging directory for the tests that do not care about the staged bytes.
     *
     * The repository needs a stager: without one it has no local file to hand over and honestly
     * refuses the item, because handing TDLib a `content://` URI as a file path is exactly the trap
     * the production wiring never hits.
     */
    private val stagingDir: java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "telepic-stage-${System.nanoTime()}")
            .apply { mkdirs() }

    private fun repo(
        cloud: CloudRepository,
        auth: MutableStateFlow<TelegramAuthState> = authorized,
        stager: BackupStager = RecordingStager(stagingDir),
    ) =
        DefaultBackupRepository(
            db = db,
            dao = db.backupQueueDao(),
            cloudRepository = cloud,
            authState = auth,
            dispatcher = Dispatchers.Unconfined,
            stager = stager,
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
    fun `re-queueing a failed item is real work, not already backed up`() = runBlocking {
        val r = repo(FakeCloud(uploadError = CloudUploadRejectedException("Unsupported media")))
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        val dao = db.backupQueueDao()
        assertEquals(BackupState.FAILED.name, dao.observeAll().first().first().state)

        // Tapping "Back up" again has to re-queue it. The unique index used to swallow the insert and
        // the item was reported as "already backed up" — a false claim about a photo that had failed,
        // with the Backup Center as the only place left to retry it.
        assertTrue(r.enqueue(media(1)))
        assertEquals(BackupState.QUEUED.name, dao.observeAll().first().first().state)
    }

    @Test
    fun `a backed-up item is still never re-queued`() = runBlocking {
        val r = repo(FakeCloud())
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        assertEquals(BackupState.BACKED_UP.name, db.backupQueueDao().observeAll().first().first().state)

        // Terminal on purpose: re-queueing it would post a second copy of the same photo.
        assertEquals(false, r.enqueue(media(1)))
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
    fun `pre-send backstop completes the row when the manifest already holds the bytes`() = runBlocking {
        // The reinstall race: recognition enqueued against a manifest this install had not yet
        // rebuilt from the channel. findRemoteByContent rebuilds it before answering on this path,
        // and the backstop must finish the row WITHOUT sending — else every item queued pre-rebuild
        // becomes a duplicate.
        val cloud = FakeCloud(remoteByContent = RemoteMediaIdentity(100L, 600L))
        val r = repo(cloud)
        assertTrue(r.enqueue(media(1), "hash-1", 1000L))

        val summary = r.processPendingWork(maxItems = 1)

        assertEquals(1, cloud.findRemoteCalls)
        assertEquals(0, cloud.uploadCalls)
        assertEquals(1, summary.recognized)
        assertEquals(0, summary.uploaded)
        assertEquals(1, summary.processed)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(100L, row.telegramChatId)
        assertEquals(600L, row.telegramMessageId)
    }

    @Test
    fun `pre-send backstop uploads when the manifest has no match`() = runBlocking {
        // The default fake knows of no remote content: the ordinary path must be untouched —
        // destination, upload, identity persisted — and the run must report a real upload.
        val cloud = FakeCloud()
        val r = repo(cloud)
        assertTrue(r.enqueue(media(1), "hash-1", 1000L))

        val summary = r.processPendingWork(maxItems = 1)

        assertEquals(1, cloud.findRemoteCalls)
        assertEquals(1, summary.uploaded)
        assertEquals(0, summary.recognized)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(500L, row.telegramMessageId)
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
    fun `account switch requeues remote-backed rows and leaves cancelled rows alone`() = runBlocking {
        val dao = db.backupQueueDao()
        val r = repo(FakeCloud())
        r.enqueue(media(1))
        val backed = dao.observeAll().first().first()
        val now = System.currentTimeMillis()
        dao.markPreparing(backed.id, now)
        dao.markUploading(backed.id, now)
        dao.markBackedUp(backed.id, 100L, 500L, 7, now)

        r.enqueue(media(2))
        val cancelled = dao.observeAll().first().first { it.localMediaId == "2" }
        dao.cancel(cancelled.id, now)

        r.resetForAccountSwitch()

        val rows = dao.observeAll().first()
        val requeued = rows.first { it.localMediaId == "1" }
        assertEquals(BackupState.QUEUED.name, requeued.state)
        assertNull(requeued.telegramChatId)
        assertNull(requeued.telegramMessageId)
        assertNull(requeued.telegramFileId)
        assertEquals(0, requeued.retryCount)

        val stillCancelled = rows.first { it.localMediaId == "2" }
        assertEquals(BackupState.CANCELLED.name, stillCancelled.state)
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

    // --- staging lifecycle (§5/§26: temp files must be cleaned safely on every path) ----------

    /** A stager that materializes a real temp file so cleanup can be observed on disk. */
    private class RecordingStager(
        private val stagingDir: java.io.File,
        /** What the staging pass reports it could read about the source item, if anything. */
        private val width: Int? = null,
        private val height: Int? = null,
        private val durationMs: Long? = null,
    ) : BackupStager {
        override suspend fun stage(localMediaId: String, contentUri: String): StagedFile {
            val file = java.io.File(stagingDir, "staging_$localMediaId.tmp")
            file.writeText("staged bytes")
            return StagedFile(file, width = width, height = height, durationMs = durationMs)
        }
    }

    /**
     * The dimensions the staging pass read must reach the upload request.
     *
     * They used to be hard-coded `null` at the request construction, so every video and GIF went to
     * Telegram with 0×0 and duration 0 — Telegram's metadata was whatever TDLib happened to probe,
     * and nothing in the app ever said what the media actually was. The queue row cannot supply
     * these (it stores identity and state, not presentation metadata), so staging is the only place
     * left that can reach the source row.
     */
    @Test
    fun `the upload request carries the staged item's dimensions`() = runBlocking {
        val cloud = FakeCloud()
        val r = repo(
            cloud,
            stager = RecordingStager(stagingDir, width = 1080, height = 1920, durationMs = 12_000L),
        )
        r.enqueue(media(21L))
        r.processPendingWork(maxItems = 10)

        val request = requireNotNull(cloud.lastRequest) { "the item was never handed to the cloud" }
        assertEquals(1080, request.width ?: -1)
        assertEquals(1920, request.height ?: -1)
        assertEquals(12_000L, request.durationMs ?: -1L)
    }

    /**
     * And when staging could not read them, they stay `null` — "unknown" — rather than becoming a
     * fabricated `0` that claims the media has no dimensions. The mapper turns `null` into 0 anyway,
     * but the request is the boundary that has to stay honest.
     */
    @Test
    fun `unknown dimensions stay null rather than becoming zero`() = runBlocking {
        val cloud = FakeCloud()
        val r = repo(cloud)
        r.enqueue(media(22L))
        r.processPendingWork(maxItems = 10)

        val request = requireNotNull(cloud.lastRequest) { "the item was never handed to the cloud" }
        assertNull(request.width)
        assertNull(request.height)
        assertNull(request.durationMs)
    }

    @Test
    fun `an upload that lands after the user cancelled is not counted as uploaded`() = runBlocking {
        // The row is cancelled while the upload is in flight, so `markBackedUp` is a SQL no-op: the
        // message still reached Telegram, but the queue refused to record it. Reporting that as an
        // upload overstated the run's progress — and that count is what the foreground notification
        // shows the user — so it is counted separately and as something with nothing left to retry.
        val cloud = FakeCloud()
        val r = repo(cloud)
        r.enqueue(media(1))
        val itemId = db.backupQueueDao().observeAll().first().single().id
        cloud.beforeUploadReturn = { r.cancel(itemId) }

        val summary = r.processPendingWork(maxItems = 1)

        assertEquals(0, summary.uploaded)
        assertEquals(1, summary.superseded)
        // Not retryable work either: the row is terminal and must not ask WorkManager to run again.
        assertEquals(false, summary.hasRetryableWork)
        val stored = db.backupQueueDao().observeAll().first().single()
        assertEquals(BackupState.CANCELLED, BackupState.fromName(stored.state))
    }

    @Test
    fun `staging file is cleaned up after a successful upload`() = runBlocking {        val stagingDir = java.io.File(System.getProperty("java.io.tmpdir"), "telepic-stage-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val r = DefaultBackupRepository(
                db = db,
                dao = db.backupQueueDao(),
                cloudRepository = FakeCloud(),
                authState = authorized,
                dispatcher = Dispatchers.Unconfined,
                stager = RecordingStager(stagingDir),
            )
            r.enqueue(media(1))
            assertEquals(1, r.processPendingWork(maxItems = 1).uploaded)
            // The staged temp file must be removed once the upload completes.
            assertTrue("staging left behind: ${stagingDir.listFiles()?.map { it.name }}", stagingDir.listFiles().isNullOrEmpty())
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    @Test
    fun `staging file is cleaned up even when the upload is cancelled`() = runBlocking {
        val stagingDir = java.io.File(System.getProperty("java.io.tmpdir"), "telepic-stage-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val cloud = FakeCloud(uploadError = kotlinx.coroutines.CancellationException("worker cancelled"))
            val r = DefaultBackupRepository(
                db = db,
                dao = db.backupQueueDao(),
                cloudRepository = cloud,
                authState = authorized,
                dispatcher = Dispatchers.Unconfined,
                stager = RecordingStager(stagingDir),
            )
            r.enqueue(media(1))
            // The cooperative cancellation is rethrown (so recovery can return the row to QUEUED)...
            var thrown: Throwable? = null
            try {
                r.processPendingWork(maxItems = 1)
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue("expected CancellationException to propagate", thrown is kotlinx.coroutines.CancellationException)
            // ...but the staged temp file must STILL be cleaned up — the bug this guards.
            assertTrue("staging leaked on cancellation: ${stagingDir.listFiles()?.map { it.name }}", stagingDir.listFiles().isNullOrEmpty())
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    // --- Pending remote identity: confirm instead of re-sending (duplicate-upload guarantee) ----

    @Test
    fun `a rejected upload clears the pending identity so a retry uploads again`() = runBlocking {
        // TDLib accepted the send and then rejected it. The pending identity pointed at a message
        // that will never be delivered, so every later attempt took the *confirm* branch, found the
        // dead message, failed again, and the row could never be uploaded — however many times the
        // user pressed Retry.
        val cloud = FakeCloud(
            sentIdentity = 100L to 500L,
            uploadError = CloudUploadRejectedException("Unsupported media"),
        )
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)

        val failed = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.FAILED.name, failed.state)
        assertNull(
            "a rejected message can never be confirmed, so its identity must be cleared",
            failed.pendingTelegramMessageId,
        )

        // The user's Retry: the next attempt must send again, not confirm the dead message.
        cloud.uploadError = null
        cloud.uploadResult = CloudUploadResult(100L, 501L, 8, CloudMediaType.IMAGE)
        r.retry(failed.id)
        val summary = r.processPendingWork(maxItems = 1)
        assertEquals(1, summary.uploaded)
        assertEquals("the file must be sent again, not confirmed", 2, cloud.uploadCalls)
    }

    @Test
    fun `a row cancelled before the upload starts sends nothing`() = runBlocking {
        // The user cancels between the queue fetch and the upload. The state write is
        // terminal-guarded, so its affected-row count is 0 — and the upload must not go ahead: an
        // explicit cancel that still sends the photo is the opposite of what was asked for.
        val stagingDir = java.io.File(System.getProperty("java.io.tmpdir"), "telepic-stage-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val cloud = FakeCloud()
            lateinit var r: DefaultBackupRepository
            val stager = object : BackupStager {
                override suspend fun stage(localMediaId: String, contentUri: String): StagedFile {
                    // The cancel lands while the file is being staged — after the row was claimed
                    // for preparation but before the upload begins.
                    r.cancel(db.backupQueueDao().observeAll().first().single().id)
                    val file = java.io.File(stagingDir, "staging_$localMediaId.tmp")
                    file.writeText("staged bytes")
                    return StagedFile(file)
                }
            }
            r = DefaultBackupRepository(
                db = db,
                dao = db.backupQueueDao(),
                cloudRepository = cloud,
                authState = authorized,
                dispatcher = Dispatchers.Unconfined,
                stager = stager,
            )
            r.enqueue(media(1))
            val summary = r.processPendingWork(maxItems = 1)

            assertEquals("nothing may be sent after an explicit cancel", 0, cloud.uploadCalls)
            assertEquals(0, summary.uploaded)
            val row = db.backupQueueDao().observeAll().first().single()
            assertEquals(BackupState.CANCELLED.name, row.state)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    @Test
    fun `a still-uploading send keeps the staged copy so Telegram can finish reading it`() = runBlocking {
        // The staged file IS the file TDLib is uploading. Deleting it while the send is in flight
        // aborted the transfer and failed a large video permanently.
        val stagingDir = java.io.File(System.getProperty("java.io.tmpdir"), "telepic-stage-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val cloud = FakeCloud(
                sentIdentity = 100L to 500L,
                uploadError = com.telepic.data.cloud.CloudMessagePendingException(),
            )
            val r = DefaultBackupRepository(
                db = db,
                dao = db.backupQueueDao(),
                cloudRepository = cloud,
                authState = authorized,
                dispatcher = Dispatchers.Unconfined,
                stager = RecordingStager(stagingDir),
            )
            r.enqueue(media(1))
            r.processPendingWork(maxItems = 1)

            assertEquals(
                "the staged copy must survive while Telegram is still uploading it",
                1,
                stagingDir.listFiles()?.size,
            )
            val row = db.backupQueueDao().observeAll().first().single()
            assertEquals(500L, row.pendingTelegramMessageId) // still confirmable, never re-sent
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    @Test
    fun `an accepted send that times out is confirmed on the next run without re-uploading`() = runBlocking {
        val cloud = FakeCloud(
            sentIdentity = 100L to 500L,
            uploadError = CloudNetworkException("Upload confirmation timed out"),
        )
        val r = repo(cloud)
        r.enqueue(media(1))
        assertEquals(0, r.processPendingWork(maxItems = 1).uploaded)

        val waiting = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, waiting.state)
        // The critical bit: the accepted send's identity is on the row, not just in TDLib's memory.
        assertEquals(100L, waiting.pendingTelegramChatId)
        assertEquals(500L, waiting.pendingTelegramMessageId)

        // Telegram finished delivering in the meantime; the next run CONFIRMS — it never re-sends.
        cloud.uploadError = null
        cloud.confirmResult = CloudUploadResult(100L, 500L, 7, CloudMediaType.IMAGE)
        val summary = r.processPendingWork(maxItems = 1)
        assertEquals(1, summary.uploaded)
        assertEquals(1, cloud.uploadCalls) // exactly one sendMessage across both runs
        assertEquals(1, cloud.confirmCalls)

        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(500L, row.telegramMessageId)
        assertNull("confirmation must clear the pending identity", row.pendingTelegramMessageId)
    }

    @Test
    fun `a still-sending confirmation waits without burning the retry budget`() = runBlocking {
        val cloud = FakeCloud(sentIdentity = 100L to 500L, uploadError = CloudNetworkException("timeout"))
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1) // run 1 burns one transient retry
        val afterFirst = db.backupQueueDao().observeAll().first().first()
        assertEquals(1, afterFirst.retryCount)

        cloud.uploadError = null
        cloud.confirmError = com.telepic.data.cloud.CloudMessagePendingException()
        r.processPendingWork(maxItems = 1)
        val row = db.backupQueueDao().observeAll().first().first()
        // A long video send must not exhaust its budget while Telegram is legitimately uploading.
        assertEquals(1, row.retryCount)
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, row.state)
        assertEquals(500L, row.pendingTelegramMessageId)
        assertEquals(1, cloud.uploadCalls) // never re-sent while pending
    }

    @Test
    fun `a send that provably never landed clears pending and re-uploads exactly once`() = runBlocking {
        val cloud = FakeCloud(sentIdentity = 100L to 500L, uploadError = CloudNetworkException("timeout"))
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)

        // TDLib lost the un-flushed send: gone → this run re-sends (and records the new identity).
        cloud.uploadError = null
        cloud.uploadResult = CloudUploadResult(100L, 501L, 8, CloudMediaType.IMAGE)
        cloud.confirmError = com.telepic.data.cloud.CloudMessageGoneException()
        val summary = r.processPendingWork(maxItems = 1)
        assertEquals(1, summary.uploaded)
        assertEquals(2, cloud.uploadCalls) // one send per run: the gone-confirmed row re-uploads once
        assertEquals(1, cloud.confirmCalls) // confirm attempted, proven gone, then a real re-upload

        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(501L, row.telegramMessageId)
        assertNull(row.pendingTelegramMessageId)
    }

    @Test
    fun `an unexpected confirm failure is recorded as transient instead of crashing the batch`() = runBlocking {
        // Run 1: the send is accepted, then confirmation times out, so the pending remote identity is
        // persisted and the row parks in WAITING_FOR_NETWORK.
        val cloud = FakeCloud(sentIdentity = 100L to 500L, uploadError = CloudNetworkException("timeout"))
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1)
        assertEquals(500L, db.backupQueueDao().observeAll().first().first().pendingTelegramMessageId)

        // Run 2: confirmUpload throws something no branch models (e.g. a Room/SQLite error out of
        // manifestDao.upsertAll). Before the fix this escaped processOne() and killed the whole batch.
        cloud.uploadError = null
        cloud.confirmError = RuntimeException("SQLiteException: disk I/O error")
        val summary = r.processPendingWork(maxItems = 1)

        assertEquals(1, summary.waiting)
        val row = db.backupQueueDao().observeAll().first().first()
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, row.state)
        assertEquals(2, row.retryCount) // run 1's network timeout + run 2's unclassified failure
        // The pending identity survives, so the item is still confirmable and is never re-sent.
        assertEquals(500L, row.pendingTelegramMessageId)
        assertEquals(1, cloud.uploadCalls)
    }

    @Test
    fun `parked pending waits live on the row so a restart cannot reset the wait cap`() = runBlocking {
        val cloud = FakeCloud(sentIdentity = 100L to 500L, uploadError = CloudNetworkException("timeout"))
        var r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1) // run 1: send accepted, timeout → pending identity persisted
        cloud.uploadError = null
        cloud.confirmError = com.telepic.data.cloud.CloudMessagePendingException()
        repeat(3) { r.processPendingWork(maxItems = 1) }
        var row = db.backupQueueDao().observeAll().first().first()
        assertEquals(3, row.pendingWaitAttempts)
        assertEquals(1, row.retryCount) // parked waits never burn the budget

        // Process death: a fresh repository over the same database resumes the count. The old
        // process-scoped map restarted at zero here, granting the stuck send another full cap of
        // waits on every restart — exactly the unbounded edge the column closes.
        r = repo(cloud)
        r.processPendingWork(maxItems = 1)
        row = db.backupQueueDao().observeAll().first().first()
        assertEquals(4, row.pendingWaitAttempts)
        assertEquals(1, row.retryCount)
        assertEquals(1, cloud.uploadCalls) // still confirmed, never re-sent
    }

    @Test
    fun `the pending wait cap burns the retry budget once and resets the counter for the next cycle`() = runBlocking {
        val cloud = FakeCloud(sentIdentity = 100L to 500L, uploadError = CloudNetworkException("timeout"))
        val r = repo(cloud)
        r.enqueue(media(1))
        r.processPendingWork(maxItems = 1) // run 1: transient timeout — retryCount 1, no parked waits yet
        cloud.uploadError = null
        cloud.confirmError = com.telepic.data.cloud.CloudMessagePendingException()

        // Up to the cap: each parked confirmation adds a wait without failing the row. The cap is
        // the number of waits that are *granted*, so the counter reaches it exactly.
        repeat(DefaultBackupRepository.MAX_PENDING_WAIT_ATTEMPTS) { r.processPendingWork(maxItems = 1) }
        var row = db.backupQueueDao().observeAll().first().first()
        assertEquals(DefaultBackupRepository.MAX_PENDING_WAIT_ATTEMPTS, row.pendingWaitAttempts)
        assertEquals(1, row.retryCount)

        // The cap boundary: one confirmation past the cap is a bounded transient failure — the
        // budget finally decrements and the counter resets so the next cycle starts clean.
        r.processPendingWork(maxItems = 1)
        row = db.backupQueueDao().observeAll().first().first()
        assertEquals(2, row.retryCount)
        assertNull(row.pendingWaitAttempts)
        assertEquals(BackupState.WAITING_FOR_NETWORK.name, row.state)
        assertEquals(500L, row.pendingTelegramMessageId) // still confirmable, never re-sent
        assertEquals(1, cloud.uploadCalls)
    }
}
