package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.cloud.db.CloudMediaManifestDao
import com.telepic.data.cloud.db.CloudMediaManifestEntity
import com.telepic.data.cloud.db.TelepicDatabase
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

    private fun row(
        chat: Long,
        msg: Long,
        hash: String?,
        size: Long?,
        name: String? = null,
        contentSize: Long? = null,
    ) = CloudMediaManifestEntity(
        chatId = chat, messageId = msg, mediaType = "IMAGE", mimeType = "image/jpeg", fileName = name,
        sizeBytes = size, width = null, height = null, durationMs = null, dateEpochSec = 1L,
        previewFileId = null, originalFileId = null, isDownloaded = false, contentHash = hash,
        contentSizeBytes = contentSize, createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `findByContentHashAndSize returns the earliest matching message id`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 50, "h", 10L), row(100, 9, "h", 10L), row(100, 30, "other", 10L)))
        assertEquals(9L, manifest.findByContentHashAndSize("h", 10L)?.messageId)
    }

    @Test
    fun `findByContentHashAndSize requires a size-consistent match`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, "h", 10L)))
        assertNotNull(manifest.findByContentHashAndSize("h", 10L))
        assertNull(manifest.findByContentHashAndSize("h", 11L))
    }

    /**
     * A row written before `contentSizeBytes` existed (migration 9 -> 10 leaves it NULL) must still
     * match on its `sizeBytes`. Without the COALESCE it would be invisible to recognition and the
     * item would be re-uploaded.
     */
    @Test
    fun `a legacy row with no contentSizeBytes still matches on sizeBytes`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, "h", 10L, contentSize = null)))
        assertNotNull(manifest.findByContentHashAndSize("h", 10L))
    }

    /** The hashed size wins when both are present, because that is the size the hash covers. */
    @Test
    fun `contentSizeBytes takes precedence over sizeBytes when both are present`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, "h", size = 999L, contentSize = 10L)))
        assertNotNull(manifest.findByContentHashAndSize("h", 10L))
        assertNull(manifest.findByContentHashAndSize("h", 999L))
    }

    @Test
    fun `findHashlessByNameAndSize matches only rows without a hash`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, null, 10L, name = "clip.mp4")))
        assertEquals(9L, manifest.findHashlessByNameAndSize("clip.mp4", 10L)?.messageId)
        // A size mismatch is not a match.
        assertNull(manifest.findHashlessByNameAndSize("clip.mp4", 11L))
        // A different name is not a match.
        assertNull(manifest.findHashlessByNameAndSize("other.mp4", 10L))
    }

    /**
     * The whole point of the `contentHash IS NULL` guard: a row that already carries a hash must be
     * matched by hash, never by a filename that could collide.
     */
    @Test
    fun `findHashlessByNameAndSize ignores a row that already has a hash`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, "h", 10L, name = "clip.mp4")))
        assertNull(manifest.findHashlessByNameAndSize("clip.mp4", 10L))
    }

    @Test
    fun `setContentIdentity persists hash and size and reports whether it applied`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, null, 10L)))
        assertEquals(1, manifest.setContentIdentity(100L, 9L, "h", 10L, 2L))
        val updated = manifest.findByContentHashAndSize("h", 10L)
        assertEquals(9L, updated?.messageId)
        assertEquals(10L, updated?.contentSizeBytes)
    }

    /** Idempotent by design: a row that already has a hash is never rewritten, and 0 says so. */
    @Test
    fun `setContentIdentity never overwrites an existing hash`() = runBlocking {
        manifest.upsertAll(listOf(row(100, 9, "existing", 10L)))
        assertEquals(0, manifest.setContentIdentity(100L, 9L, "recovered", 10L, 2L))
        assertNotNull(manifest.findByContentHashAndSize("existing", 10L))
        assertNull(manifest.findByContentHashAndSize("recovered", 10L))
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
