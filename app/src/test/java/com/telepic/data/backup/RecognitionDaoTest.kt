package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.cloud.db.CloudMediaManifestDao
import com.telepic.data.cloud.db.CloudMediaManifestEntity
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the content-hash queries recognition relies on, against the real (indexed) schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecognitionDaoTest {

    private lateinit var db: TelepicDatabase
    private lateinit var manifest: CloudMediaManifestDao
    private lateinit var queue: BackupQueueDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java).allowMainThreadQueries().build()
        manifest = db.cloudMediaManifestDao()
        queue = db.backupQueueDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(chat: Long, msg: Long, hash: String?, size: Long?) = CloudMediaManifestEntity(
        chatId = chat, messageId = msg, mediaType = "IMAGE", mimeType = "image/jpeg", fileName = null,
        sizeBytes = size, width = null, height = null, durationMs = null, dateEpochSec = 1L,
        previewFileId = null, originalFileId = null, isDownloaded = false, contentHash = hash,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `findByContentHash returns the earliest matching message id`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 50, "h", 10L), row(100, 9, "h", 10L), row(100, 30, "other", 10L)))
        assertEquals(9L, manifest.findByContentHash("h")?.messageId)
    }

    @Test
    fun `findByContentHashAndSize requires a size-consistent match`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, "h", 10L)))
        assertNotNull(manifest.findByContentHashAndSize("h", 10L))
        assertNull(manifest.findByContentHashAndSize("h", 11L))
    }

    @Test
    fun `setContentHash persists a trusted hash onto an existing row`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, null, 10L)))
        manifest.setContentHash(100L, 9L, "h", 2L)
        assertEquals("h", manifest.findByContentHash("h")?.contentHash)
    }

    @Test
    fun `queue findActiveByLocalId ignores terminal rows`() = runBlocking {
        val entity = queueEntityFor("content://x")
        val rowId = queue.insertIgnore(entity)
        assertNotNull(queue.findActiveByLocalId(entity.localMediaId))
        queue.markBackedUp(rowId, 100L, 9L, null, 2L)
        assertNull(queue.findActiveByLocalId(entity.localMediaId))
        // findByLocalId still returns the terminal row.
        assertNotNull(queue.findByLocalId(entity.localMediaId))
    }

    @Test
    fun `persistHash stores hash, size and hashedAt`() = runBlocking {
        val entity = queueEntityFor("content://y")
        val rowId = queue.insertIgnore(entity)
        queue.persistHash(rowId, "h", 123L, 9L, 9L)
        val reloaded = queue.findByLocalId(entity.localMediaId)!!
        assertEquals("h", reloaded.contentHash)
        assertEquals(123L, reloaded.contentSizeBytes)
        assertEquals(9L, reloaded.hashedAt)
    }

    private fun queueEntityFor(uriString: String): com.telepic.data.backup.db.BackupQueueEntity {
        val uri = android.net.Uri.parse(uriString)
        val media = com.telepic.domain.media.LocalMedia(
            id = uriString.hashCode().toLong(), contentUri = uri, type = com.telepic.domain.media.MediaType.PHOTO,
            mimeType = "image/jpeg", displayName = null, dateMillis = 1_000L, durationMillis = null, width = 1,
            height = 1, sizeBytes = 1L, bucketId = null, bucketName = null, relativePath = null,
        )
        return with(com.telepic.data.backup.BackupMapping) { media.toQueueEntity(1L) }
    }
}
