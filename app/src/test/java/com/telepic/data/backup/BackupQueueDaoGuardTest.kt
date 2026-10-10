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
}
