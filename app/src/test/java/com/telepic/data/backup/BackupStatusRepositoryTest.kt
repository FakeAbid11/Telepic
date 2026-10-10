package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.domain.backup.BackupState
import com.telepic.domain.backup.MediaBackupVisualState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Photos backup-status map is built from the batched Room queue, never per tile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupStatusRepositoryTest {

    private lateinit var db: TelepicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun row(id: Long, state: BackupState) = BackupQueueEntity(
        localMediaId = id.toString(),
        contentUri = "content://m/$id",
        mediaType = "IMAGE",
        mimeType = "image/jpeg",
        fileName = null,
        sizeBytes = 10L,
        modifiedTimeSeconds = 1L,
        state = state.name,
        retryCount = 0,
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

    @Test
    fun `maps each queue row to its compact visual state`() = runBlocking {
        val dao = db.backupQueueDao()
        dao.insertIgnore(row(1, BackupState.BACKED_UP))
        dao.insertIgnore(row(2, BackupState.UPLOADING))
        dao.insertIgnore(row(3, BackupState.QUEUED))
        dao.insertIgnore(row(4, BackupState.FAILED))
        dao.insertIgnore(row(5, BackupState.CANCELLED)) // shows no indicator

        val map = DefaultBackupStatusRepository(dao).visualStates.first()
        assertEquals(MediaBackupVisualState.BACKED_UP, map["1"])
        assertEquals(MediaBackupVisualState.UPLOADING, map["2"])
        assertEquals(MediaBackupVisualState.QUEUED, map["3"])
        assertEquals(MediaBackupVisualState.FAILED, map["4"])
        assertEquals(MediaBackupVisualState.NONE, map["5"])
    }

    @Test
    fun `no queue rows produce an empty map`() = runBlocking {
        assertEquals(emptyMap<String, MediaBackupVisualState>(), DefaultBackupStatusRepository(db.backupQueueDao()).visualStates.first())
    }
}
