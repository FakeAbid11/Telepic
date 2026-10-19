package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The terminal-state invariants enforced *in SQL* (not by caller discipline): a late worker write
 * can never overwrite a CANCELLED/FAILED/BACKED_UP row, a cancel can never undo a completed upload,
 * and only a deliberate manual retry resurrects FAILED/CANCELLED — never BACKED_UP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupQueueDaoGuardTest {

    private lateinit var db: TelepicDatabase
    private lateinit var dao: BackupQueueDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.backupQueueDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(state: String, id: Long = 0, retryCount: Int = 0) = BackupQueueEntity(
        id = id,
        localMediaId = "media-${state}-${retryCount}-$id",
        contentUri = "content://media/1",
        mediaType = "IMAGE",
        mimeType = "image/jpeg",
        fileName = "p.jpg",
        sizeBytes = 10L,
        modifiedTimeSeconds = 1L,
        state = state,
        retryCount = retryCount,
        lastError = null,
        telegramChatId = null,
        telegramMessageId = null,
        telegramFileId = null,
        createdAt = 1L,
        updatedAt = 1L,
        startedAt = null,
        completedAt = null,
        contentHash = null,
    )

    private suspend fun insert(entity: BackupQueueEntity): Long = dao.insertIgnore(entity)

    private suspend fun find(localMediaId: String) = dao.findByLocalId(localMediaId)!!

    @Test
    fun `cancel wins a mid-upload race and the late success write is a no-op`() = runBlocking {
        val mediaId = "m-cancel-race"
        insert(row("UPLOADING").copy(localMediaId = mediaId))
        val id = find(mediaId).id

        // Worker still finishing uploads the bytes; the user cancels first.
        assertEquals(1, dao.cancel(id, 2L))
        // The late completion must NOT resurrect or overwrite the cancel.
        assertEquals(0, dao.markBackedUp(id, 100L, 500L, 7, 3L))

        val stored = find(mediaId)
        assertEquals("CANCELLED", stored.state)
        assertNull(stored.telegramMessageId)
        assertNull(stored.completedAt)
    }

    @Test
    fun `a completed upload can never be cancelled`() = runBlocking {
        val mediaId = "m-backup-complete"
        insert(row("UPLOADING").copy(localMediaId = mediaId))
        val id = find(mediaId).id
        assertEquals(1, dao.markBackedUp(id, 100L, 500L, 7, 2L))

        assertEquals(0, dao.cancel(id, 3L))
        val stored = find(mediaId)
        assertEquals("BACKED_UP", stored.state)
        assertEquals(500L, stored.telegramMessageId)
    }

    @Test
    fun `a failed row never accepts automatic transitions`() = runBlocking {
        val mediaId = "m-frozen-failed"
        insert(row("FAILED", retryCount = 5).copy(localMediaId = mediaId))
        val id = find(mediaId).id

        assertEquals(0, dao.recordTransientFailure(id, "boom", 2L))
        assertEquals(0, dao.markWaiting(id, "WAITING_FOR_NETWORK", 2L))
        assertEquals(0, dao.markPreparing(id, 2L))

        val stored = find(mediaId)
        assertEquals("FAILED", stored.state)
        assertEquals(5, stored.retryCount) // no automatic retry budget burn on a terminal row
    }

    @Test
    fun `manual retry resurrects FAILED and CANCELLED but never BACKED_UP`() = runBlocking {
        val mediaId = "m-retry-policy"
        insert(row("FAILED", retryCount = 5).copy(localMediaId = mediaId))
        val failedId = find(mediaId).id
        assertEquals(1, dao.requeueForRetry(failedId, 2L))
        assertEquals("QUEUED", find(mediaId).state)

        insert(row("BACKED_UP").copy(localMediaId = "m-backed"))
        val backedId = find("m-backed").id
        // Re-queuing a confirmed upload would duplicate the remote message — SQL says no.
        assertEquals(0, dao.requeueForRetry(backedId, 3L))
        assertEquals("BACKED_UP", find("m-backed").state)
    }

    @Test
    fun `a manual retry never disturbs a row that is mid-flight`() = runBlocking {
        // Re-queuing a PREPARING/UPLOADING row would wipe the retry budget of a send that is still
        // running, and the in-flight completion would then land on a row the user had already
        // re-queued.
        for (state in listOf("PREPARING", "UPLOADING")) {
            val mediaId = "m-inflight-$state"
            insert(row(state).copy(localMediaId = mediaId))
            val id = find(mediaId).id
            assertEquals("a $state row must not be re-queued", 0, dao.requeueForRetry(id, 2L))
            assertEquals(state, find(mediaId).state)
        }
    }

    @Test
    fun `a permanent failure clears the pending remote identity`() = runBlocking {
        // A rejected message will never be delivered, so keeping its id made every later attempt
        // confirm the dead message and fail again — the row could never be uploaded, however many
        // times the user pressed Retry.
        val mediaId = "m-rejected"
        insert(row("UPLOADING").copy(localMediaId = mediaId))
        val id = find(mediaId).id
        assertEquals(1, dao.recordPendingRemote(id, 100L, 500L, 2L))
        assertEquals(500L, find(mediaId).pendingTelegramMessageId)

        assertEquals(1, dao.markFailed(id, "rejected", 3L))
        val stored = find(mediaId)
        assertEquals("FAILED", stored.state)
        assertNull("a rejected message can never be confirmed", stored.pendingTelegramMessageId)
        assertNull(stored.pendingTelegramChatId)
    }

    @Test
    fun `a manual retry keeps the pending identity so an in-flight send is confirmed, not duplicated`() = runBlocking {
        // A cancelled row may still have a send in flight on Telegram's side. Clearing its pending
        // id here would make the retry upload the same file a second time.
        val mediaId = "m-cancelled-inflight"
        insert(row("UPLOADING").copy(localMediaId = mediaId))
        val id = find(mediaId).id
        dao.recordPendingRemote(id, 100L, 500L, 2L)
        assertEquals(1, dao.cancel(id, 3L))

        assertEquals(1, dao.requeueForRetry(id, 4L))
        val stored = find(mediaId)
        assertEquals("QUEUED", stored.state)
        assertEquals(500L, stored.pendingTelegramMessageId)
    }

    @Test
    fun `an unknown waiting state is never written`() = runBlocking {
        // A mistyped state name used to be written verbatim, and the mapping then reported the row
        // as NOT_BACKED_UP — a silent loss of the row's real status.
        val mediaId = "m-bad-state"
        insert(row("QUEUED").copy(localMediaId = mediaId))
        val id = find(mediaId).id
        assertEquals(0, dao.markWaiting(id, "SOMETHING_ELSE", 2L))
        assertEquals("QUEUED", find(mediaId).state)
    }
}
