package com.telepix.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepix.data.backup.BackupMapping.toQueueEntity
import com.telepix.data.backup.BackupMapping.toRecognizedQueueEntity
import com.telepix.data.backup.db.BackupQueueDao
import com.telepix.data.backup.hash.ContentHasher
import com.telepix.data.cloud.db.CloudMediaManifestDao
import com.telepix.data.cloud.db.CloudMediaManifestEntity
import com.telepix.data.cloud.db.TelepixDatabase
import com.telepix.domain.backup.BackupRecognitionResult
import com.telepix.domain.backup.BackupState
import com.telepix.domain.backup.ContentHashResult
import com.telepix.domain.backup.RecognitionUnavailableReason
import com.telepix.domain.media.LocalMedia
import com.telepix.domain.media.MediaType
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
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
 * Content-based recognition against in-memory Room + a fake hasher. Proves the Phase 7 rule
 * (same bytes → same identity → no duplicate upload) with no Telegram/network/native lib.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRecognitionRepositoryTest {

    private lateinit var db: TelepixDatabase
    private lateinit var queueDao: BackupQueueDao
    private lateinit var manifestDao: CloudMediaManifestDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        queueDao = db.backupQueueDao()
        manifestDao = db.cloudMediaManifestDao()
    }

    @After
    fun tearDown() = db.close()

    /** Maps a content URI to a deterministic fake hash; counts how often it actually hashes. */
    private class FakeHasher(
        private val byUri: Map<String, String>,
        private val sizeFor: Long = 1000L,
        var throwable: Throwable? = null,
        var readable: Boolean = true,
    ) : ContentHasher {
        var hashCalls = 0
        override suspend fun hash(uri: Uri): ContentHashResult {
            hashCalls++
            throwable?.let { throw it }
            val h = byUri[uri.toString()] ?: throw IOException("unknown")
            return ContentHashResult(h, sizeFor)
        }
        override suspend fun hashSize(uri: Uri): Long = sizeFor
        override suspend fun isReadable(uri: Uri): Boolean = readable
    }

    private fun repo(hasher: ContentHasher) = DefaultBackupRecognitionRepository(
        queueDao = queueDao,
        manifestDao = manifestDao,
        hasher = hasher,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun media(id: Long, uri: String, size: Long = 1000L, modifiedMs: Long = 1_000_000_000_000L) = LocalMedia(
        id = id, contentUri = Uri.parse(uri), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "p$id.jpg", dateMillis = modifiedMs, durationMillis = null, width = 1, height = 1,
        sizeBytes = size, bucketId = null, bucketName = null, relativePath = null,
    )

    private fun insertManifest(chatId: Long, messageId: Long, hash: String?, size: Long?) = runBlocking {
        manifestDao.upsertAll(
            listOf(
                CloudMediaManifestEntity(
                    chatId = chatId, messageId = messageId, mediaType = "IMAGE", mimeType = "image/jpeg",
                    fileName = "x.jpg", sizeBytes = size, width = null, height = null, durationMs = null,
                    dateEpochSec = 1L, previewFileId = null, originalFileId = null, isDownloaded = false,
                    contentHash = hash, createdAt = 1L, updatedAt = 1L,
                ),
            ),
        )
    }

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun `no remote match - needs backup, carrying the computed hash`() = runBlocking {
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.NeedsBackup(hashA, 1000L), result)
        assertEquals(1, hasher.hashCalls)
    }

    @Test
    fun `remote hash+size match - already backed up and associated locally`() = runBlocking {
        insertManifest(chatId = 100L, messageId = 7L, hash = hashA, size = 1000L)
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.AlreadyBackedUp("1", com.telepix.domain.backup.RemoteMediaIdentity(100L, 7L)), result)
        // Local association row created so future scans recognize instantly.
        val row = queueDao.findByLocalId("1")!!
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(hashA, row.contentHash)
    }

    @Test
    fun `size mismatch is NOT recognized (avoids wrong skip)`() = runBlocking {
        insertManifest(chatId = 100L, messageId = 7L, hash = hashA, size = 999L)
        val hasher = FakeHasher(mapOf("content://a" to hashA), sizeFor = 1000L)
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertTrue(result is BackupRecognitionResult.NeedsBackup)
    }

    @Test
    fun `local queue already BACKED_UP - recognized without hashing`() = runBlocking {
        queueDao.insertIgnore(
            media(1, "content://a").toRecognizedQueueEntity(
                com.telepix.domain.backup.RemoteMediaIdentity(100L, 7L), hashA, 1000L, 1L,
            ),
        )
        val hasher = FakeHasher(emptyMap())
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.AlreadyBackedUp("1", com.telepix.domain.backup.RemoteMediaIdentity(100L, 7L)), result)
        assertEquals(0, hasher.hashCalls)
    }

    @Test
    fun `active queue row - pending, never re-hashed`() = runBlocking {
        queueDao.insertIgnore(media(1, "content://a").toQueueEntity(1L))
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertTrue(result is BackupRecognitionResult.Pending)
        assertEquals(0, hasher.hashCalls)
    }

    @Test
    fun `failed queue row is re-evaluated by content`() = runBlocking {
        val id = queueDao.insertIgnore(media(1, "content://a").toQueueEntity(1L))
        queueDao.markFailed(id, "boom", 2L)
        insertManifest(chatId = 100L, messageId = 7L, hash = hashA, size = 1000L)
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertTrue(result is BackupRecognitionResult.AlreadyBackedUp)
    }

    @Test
    fun `cache reuse - matching size and modified skips re-hashing`() = runBlocking {
        val id = queueDao.insertIgnore(media(1, "content://a", size = 1000L).toQueueEntity(1L, hashA, 1000L))
        queueDao.persistHash(id, hashA, 1000L, 5L, 5L)
        // Same size + same modified as the cached row (media.dateMillis default).
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        repo(hasher).recognize(media(1, "content://a", size = 1000L))
        assertEquals(0, hasher.hashCalls)
    }

    @Test
    fun `changed file size invalidates the cached hash`() = runBlocking {
        val id = queueDao.insertIgnore(media(1, "content://a", size = 1000L).toQueueEntity(1L, hashA, 1000L))
        queueDao.persistHash(id, hashA, 1000L, 5L, 5L)
        val hasher = FakeHasher(mapOf("content://a" to hashB), sizeFor = 2000L)
        repo(hasher).recognize(media(1, "content://a", size = 2000L))
        assertEquals(1, hasher.hashCalls) // re-hashed because size changed
    }

    @Test
    fun `hash failure surfaces Unavailable, never BACKED_UP`() = runBlocking {
        val hasher = FakeHasher(emptyMap(), throwable = IOException("io"), readable = true)
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.Unavailable(RecognitionUnavailableReason.HASH_FAILED), result)
    }

    @Test
    fun `unreadable source surfaces SOURCE_UNREADABLE`() = runBlocking {
        val hasher = FakeHasher(emptyMap(), throwable = IOException("gone"), readable = false)
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.Unavailable(RecognitionUnavailableReason.SOURCE_UNREADABLE), result)
    }

    @Test
    fun `identical bytes under different ids recognize via the same manifest hash`() = runBlocking {
        insertManifest(chatId = 200L, messageId = 42L, hash = hashA, size = 1000L)
        // A brand-new local identity (e.g. after reinstall) with the same content hash.
        val hasher = FakeHasher(mapOf("content://new" to hashA))
        val result = repo(hasher).recognize(media(999, "content://new"))
        assertEquals(BackupRecognitionResult.AlreadyBackedUp("999", com.telepix.domain.backup.RemoteMediaIdentity(200L, 42L)), result)
    }

    @Test
    fun `multiple remote matches choose the deterministic earliest message id`() = runBlocking {
        insertManifest(chatId = 100L, messageId = 50L, hash = hashA, size = 1000L)
        insertManifest(chatId = 100L, messageId = 10L, hash = hashA, size = 1000L)
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(
            BackupRecognitionResult.AlreadyBackedUp("1", com.telepix.domain.backup.RemoteMediaIdentity(100L, 10L)),
            result,
        )
    }
}
