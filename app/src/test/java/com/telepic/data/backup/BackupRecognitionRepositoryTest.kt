package com.telepic.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.BackupMapping.toQueueEntity
import com.telepic.data.backup.BackupMapping.toRecognizedQueueEntity
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.backup.hash.ContentHasher
import com.telepic.data.cloud.db.CloudMediaManifestDao
import com.telepic.data.cloud.db.CloudMediaManifestEntity
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.domain.backup.BackupRecognitionResult
import com.telepic.domain.backup.BackupState
import com.telepic.domain.backup.ContentHashResult
import com.telepic.domain.backup.RecognitionUnavailableReason
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
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

    private lateinit var db: TelepicDatabase
    private lateinit var queueDao: BackupQueueDao
    private lateinit var manifestDao: CloudMediaManifestDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
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
        var sizeThrowable: Throwable? = null,
        var readable: Boolean = true,
    ) : ContentHasher {
        var hashCalls = 0
        override suspend fun hash(uri: Uri): ContentHashResult {
            hashCalls++
            throwable?.let { throw it }
            val h = byUri[uri.toString()] ?: throw IOException("unknown")
            return ContentHashResult(h, sizeFor)
        }
        override suspend fun hashSize(uri: Uri): Long {
            sizeThrowable?.let { throw it }
            return sizeFor
        }
        override suspend fun isReadable(uri: Uri): Boolean = readable
    }

    private fun repo(hasher: ContentHasher) = DefaultBackupRecognitionRepository(
        db = db,
        queueDao = queueDao,
        manifestDao = manifestDao,
        hasher = hasher,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun media(
        id: Long,
        uri: String,
        size: Long = 1000L,
        modifiedMs: Long = 1_000_000_000_000L,
        displayName: String = "p$id.jpg",
    ) = LocalMedia(
        id = id, contentUri = Uri.parse(uri), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = displayName, dateMillis = modifiedMs, durationMillis = null, width = 1, height = 1,
        sizeBytes = size, bucketId = null, bucketName = null, relativePath = null,
    )

    private fun insertManifest(
        chatId: Long,
        messageId: Long,
        hash: String?,
        size: Long?,
        fileName: String? = "x.jpg",
        contentSizeBytes: Long? = null,
    ) = runBlocking {
        manifestDao.upsertAll(
            listOf(
                CloudMediaManifestEntity(
                    chatId = chatId, messageId = messageId, mediaType = "IMAGE", mimeType = "image/jpeg",
                    fileName = fileName, sizeBytes = size, width = null, height = null, durationMs = null,
                    dateEpochSec = 1L, previewFileId = null, originalFileId = null, isDownloaded = false,
                    contentHash = hash, contentSizeBytes = contentSizeBytes, createdAt = 1L, updatedAt = 1L,
                ),
            ),
        )
    }

    private suspend fun manifestRow(chatId: Long, messageId: Long) =
        manifestDao.get(chatId, messageId)

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
        assertEquals(BackupRecognitionResult.AlreadyBackedUp("1", com.telepic.domain.backup.RemoteMediaIdentity(100L, 7L)), result)
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

    // --- Reinstall recovery ---------------------------------------------------------------------
    //
    // A manifest rebuilt from the channel's history after a reinstall has rows with no hash, so the
    // hash lookup can never see them. These cover the weaker name+size path that recovers them, and
    // — just as importantly — every way it must refuse to match.

    @Test
    fun `a hashless manifest row with the same name and size is recovered and upgraded`() = runBlocking {
        insertManifest(
            chatId = 100L, messageId = 7L, hash = null, size = 1000L,
            fileName = "clip.mp4", contentSizeBytes = null,
        )
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a", displayName = "clip.mp4"))

        assertEquals(
            BackupRecognitionResult.AlreadyBackedUp("1", com.telepic.domain.backup.RemoteMediaIdentity(100L, 7L)),
            result,
        )
        // The row is upgraded in place, so the next scan matches on the hash instead of the name.
        val upgraded = manifestRow(100L, 7L)!!
        assertEquals(hashA, upgraded.contentHash)
        assertEquals(1000L, upgraded.contentSizeBytes)
    }

    /**
     * The upgraded row must be reachable by hash alone from then on — that is the whole point of
     * writing the identity back. Renaming the local file proves the name is no longer involved.
     */
    @Test
    fun `recovery is one-shot - the upgraded row matches by hash afterwards`() = runBlocking {
        insertManifest(
            chatId = 100L, messageId = 7L, hash = null, size = 1000L,
            fileName = "clip.mp4", contentSizeBytes = null,
        )
        val hasher = FakeHasher(mapOf("content://a" to hashA, "content://b" to hashA))
        repo(hasher).recognize(media(1, "content://a", displayName = "clip.mp4"))

        // A different local item, different id, different name, same bytes.
        val second = repo(hasher).recognize(media(2, "content://b", displayName = "renamed.mp4"))
        assertEquals(
            BackupRecognitionResult.AlreadyBackedUp("2", com.telepic.domain.backup.RemoteMediaIdentity(100L, 7L)),
            second,
        )
    }

    @Test
    fun `a hashless row with a different name is NOT recovered`() = runBlocking {
        insertManifest(
            chatId = 100L, messageId = 7L, hash = null, size = 1000L,
            fileName = "clip.mp4", contentSizeBytes = null,
        )
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a", displayName = "other.mp4"))
        assertTrue(result is BackupRecognitionResult.NeedsBackup)
    }

    @Test
    fun `a hashless row with a different size is NOT recovered`() = runBlocking {
        insertManifest(
            chatId = 100L, messageId = 7L, hash = null, size = 999L,
            fileName = "clip.mp4", contentSizeBytes = null,
        )
        val hasher = FakeHasher(mapOf("content://a" to hashA), sizeFor = 1000L)
        val result = repo(hasher).recognize(media(1, "content://a", displayName = "clip.mp4"))
        assertTrue(result is BackupRecognitionResult.NeedsBackup)
    }

    /**
     * A filename is not identity. A row that already carries a hash must be matched by hash, so a
     * name collision can never be mistaken for "already backed up" — which would silently skip an
     * upload the user asked for.
     */
    @Test
    fun `a row that already has a hash is never matched by name`() = runBlocking {
        insertManifest(
            chatId = 100L, messageId = 7L, hash = hashB, size = 1000L,
            fileName = "clip.mp4", contentSizeBytes = 1000L,
        )
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a", displayName = "clip.mp4"))
        assertTrue(result is BackupRecognitionResult.NeedsBackup)
    }

    @Test
    fun `a null local display name never recovers a row`() = runBlocking {
        insertManifest(
            chatId = 100L, messageId = 7L, hash = null, size = 1000L,
            fileName = null, contentSizeBytes = null,
        )
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a", displayName = ""))
        assertTrue(result is BackupRecognitionResult.NeedsBackup)
    }

    @Test
    fun `local queue already BACKED_UP - recognized without hashing`() = runBlocking {
        queueDao.insertIgnore(
            media(1, "content://a").toRecognizedQueueEntity(
                com.telepic.domain.backup.RemoteMediaIdentity(100L, 7L), hashA, 1000L, 1L,
            ),
        )
        val hasher = FakeHasher(emptyMap())
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.AlreadyBackedUp("1", com.telepic.domain.backup.RemoteMediaIdentity(100L, 7L)), result)
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
    fun `a remote match clears a FAILED row to BACKED_UP with its remote identity`() = runBlocking {
        // The bug this guards: recognition reached `markBackedUp` only for rows its own SQL denylist
        // refuses (FAILED / CANCELLED), so the write was a guaranteed no-op. Content that was provably
        // already in the cloud stayed displayed as FAILED and was re-hashed on every single scan.
        val id = queueDao.insertIgnore(media(1, "content://a").toQueueEntity(1L))
        queueDao.markFailed(id, "boom", 2L)
        insertManifest(chatId = 100L, messageId = 7L, hash = hashA, size = 1000L)
        repo(FakeHasher(mapOf("content://a" to hashA))).recognize(media(1, "content://a"))

        val row = queueDao.findByLocalId("1")!!
        assertEquals(BackupState.BACKED_UP.name, row.state)
        assertEquals(100L, row.telegramChatId)
        assertEquals(7L, row.telegramMessageId)
        assertEquals(hashA, row.contentHash)
    }

    @Test
    fun `a recognised row is not re-hashed on the next scan`() = runBlocking {
        // Proves the fix above actually reaches the cheap path: after one recognition pass the queue
        // answers from its own row, so a second scan must not touch the hasher at all.
        insertManifest(chatId = 100L, messageId = 7L, hash = hashA, size = 1000L)
        val first = FakeHasher(mapOf("content://a" to hashA))
        repo(first).recognize(media(1, "content://a"))
        assertEquals(1, first.hashCalls)

        val second = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(second).recognize(media(1, "content://a"))
        assertTrue(result is BackupRecognitionResult.AlreadyBackedUp)
        assertEquals(0, second.hashCalls)
    }

    @Test
    fun `cache reuse - matching size and modified skips re-hashing`() = runBlocking {
        val id = queueDao.insertIgnore(media(1, "content://a", size = 1000L).toQueueEntity(1L, hashA, 1000L))
        queueDao.markFailed(id, "prior transient failure", 2L)
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a", size = 1000L))
        assertTrue(result is BackupRecognitionResult.NeedsBackup)
        assertEquals(0, hasher.hashCalls) // cached hash reused (size + modified unchanged)
    }

    @Test
    fun `changed file size invalidates the cached hash`() = runBlocking {
        val id = queueDao.insertIgnore(media(1, "content://a", size = 1000L).toQueueEntity(1L, hashA, 1000L))
        queueDao.markFailed(id, "prior transient failure", 2L)
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
    fun `a failing size query degrades to Unavailable instead of escaping recognition`() = runBlocking {
        // `hashSize` is a ContentResolver query of its own and fails for the same reasons hashing
        // does. It used to sit outside the try that guards the hash, so a revoked or limited media
        // grant failed recognition outright — and, from the onboarding backup step, the whole app.
        val hasher = FakeHasher(
            byUri = mapOf("content://a" to hashA),
            sizeThrowable = SecurityException("media access was revoked"),
        )
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(BackupRecognitionResult.Unavailable(RecognitionUnavailableReason.HASH_FAILED), result)
    }

    @Test
    fun `a cancelled scan is rethrown, never downgraded to a hash failure`() = runBlocking {
        // Swallowing cancellation here kept a rotating or closing screen hashing the whole library
        // after its coroutine had been cancelled.
        val hasher = FakeHasher(mapOf("content://a" to hashA), throwable = CancellationException("stopped"))
        val thrown = runCatching { repo(hasher).recognize(media(1, "content://a")) }.exceptionOrNull()
        assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
    }

    @Test
    fun `identical bytes under different ids recognize via the same manifest hash`() = runBlocking {
        insertManifest(chatId = 200L, messageId = 42L, hash = hashA, size = 1000L)
        // A brand-new local identity (e.g. after reinstall) with the same content hash.
        val hasher = FakeHasher(mapOf("content://new" to hashA))
        val result = repo(hasher).recognize(media(999, "content://new"))
        assertEquals(BackupRecognitionResult.AlreadyBackedUp("999", com.telepic.domain.backup.RemoteMediaIdentity(200L, 42L)), result)
    }

    @Test
    fun `multiple remote matches choose the deterministic earliest message id`() = runBlocking {
        insertManifest(chatId = 100L, messageId = 50L, hash = hashA, size = 1000L)
        insertManifest(chatId = 100L, messageId = 10L, hash = hashA, size = 1000L)
        val hasher = FakeHasher(mapOf("content://a" to hashA))
        val result = repo(hasher).recognize(media(1, "content://a"))
        assertEquals(
            BackupRecognitionResult.AlreadyBackedUp("1", com.telepic.domain.backup.RemoteMediaIdentity(100L, 10L)),
            result,
        )
    }
}
