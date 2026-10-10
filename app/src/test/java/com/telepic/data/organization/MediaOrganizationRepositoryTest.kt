package com.telepic.data.organization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.organization.db.MediaOrganizationDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Favorites / Archive / Trash persistence against the real Room schema, and the guarantee that
 * organization changes never mutate backup state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaOrganizationRepositoryTest {

    private lateinit var db: TelepicDatabase
    private lateinit var repository: DefaultMediaOrganizationRepository
    private lateinit var queue: BackupQueueDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java).allowMainThreadQueries().build()
        repository = DefaultMediaOrganizationRepository(db.mediaOrganizationDao(), clock = { 1_000L })
        queue = db.backupQueueDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `favorite persists and survives repository recreation`() = runBlocking {
        repository.setFavorite(7L, true)
        val dao: MediaOrganizationDao = db.mediaOrganizationDao()
        assertEquals(setOf(7L), repository.favoriteIds.first())
        assertTrue(repository.isFavorite(7L))

        val reopened = DefaultMediaOrganizationRepository(dao, clock = { 2_000L })
        assertTrue(reopened.isFavorite(7L))
        assertFalse(reopened.isArchived(7L))
    }

    @Test
    fun `unfavorite clears the flag`() = runBlocking {
        repository.setFavorite(3L, true)
        repository.setFavorite(3L, false)
        assertFalse(repository.isFavorite(3L))
    }

    @Test
    fun `archived and trashed ids are hidden but favorites are not`() = runBlocking {
        repository.setFavorite(1L, true)
        repository.setArchived(2L, true)
        repository.moveToTrash(3L)
        val hidden = repository.hiddenIds()
        assertTrue(hidden.contains(2L))
        assertTrue(hidden.contains(3L))
        assertFalse(hidden.contains(1L))
    }

    @Test
    fun `restoring from trash removes it from the hidden set`() = runBlocking {
        repository.moveToTrash(9L)
        assertTrue(repository.hiddenIds().contains(9L))
        repository.restoreFromTrash(9L)
        assertFalse(repository.hiddenIds().contains(9L))
        assertFalse(repository.isTrashed(9L))
    }

    @Test
    fun `markDeletedFromStore records the honest file-level flag without clearing trash state`() = runBlocking {
        repository.moveToTrash(4L)
        repository.markDeletedFromStore(4L)
        val row = db.mediaOrganizationDao().get("4")!!
        assertTrue(row.deletedFromStore)
        assertTrue(row.isTrashed)
    }

    @Test
    fun `remove forgets all organization state for an id`() = runBlocking {
        repository.setFavorite(5L, true)
        repository.remove(5L)
        assertFalse(repository.isFavorite(5L))
        assertEquals(emptySet<Long>(), repository.favoriteIds.first())
    }

    @Test
    fun `organization changes never touch the backup queue`() = runBlocking {
        queue.insertIgnore(newQueueRow(localMediaId = "7", state = "BACKED_UP"))
        repository.setFavorite(7L, true)
        repository.moveToTrash(7L)
        val after = queue.observeStatusRows().first().single()
        assertEquals("BACKED_UP", after.state)
    }

    private fun newQueueRow(localMediaId: String, state: String) = BackupQueueEntity(
        localMediaId = localMediaId, contentUri = "content://m/$localMediaId", mediaType = "IMAGE",
        mimeType = "image/jpeg", fileName = null, sizeBytes = 10L, modifiedTimeSeconds = 1L,
        state = state, retryCount = 0, lastError = null, telegramChatId = 1L, telegramMessageId = 1L,
        telegramFileId = 1, createdAt = 1L, updatedAt = 1L, startedAt = null, completedAt = null,
        contentHash = null,
    )
}
