package com.telepix.data.backup

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.telepix.data.cloud.db.CloudDestinationDao
import com.telepix.data.cloud.db.CloudDestinationEntity
import com.telepix.data.cloud.db.CloudMediaManifestDao
import com.telepix.data.cloud.db.CloudMediaManifestEntity
import com.telepix.data.cloud.db.TelepixDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mirrors the Phase 5 (version 1) schema exactly — two entities, no backup queue. */
@Database(entities = [CloudDestinationEntity::class, CloudMediaManifestEntity::class], version = 1, exportSchema = false)
abstract class Phase5Database : RoomDatabase() {
    abstract fun cloudDestinationDao(): CloudDestinationDao
    abstract fun cloudMediaManifestDao(): CloudMediaManifestDao
}

/**
 * Verifies the *actual* Phase 5 → Phase 6 Room migration, not just creation of the newest schema:
 * a database built at version 1 (cloud destination + manifest only), with real rows, is reopened at
 * version 2 through [TelepixDatabase.MIGRATION_1_2], and the Phase 5 data must survive while the new
 * backup_queue table becomes usable. No destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMigrationTest {

    @Test
    fun `phase 5 data survives the 1 to 2 migration and the queue is usable`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_phase6.db"
        context.deleteDatabase(dbFile)

        // Arrange: create a real version-1 database with a destination + a manifest row.
        val v1 = Room.databaseBuilder(context, Phase5Database::class.java, dbFile)
            .allowMainThreadQueries()
            .build()
        v1.cloudDestinationDao().upsert(
            CloudDestinationEntity("telegram", 100L, "Telepix Backup", true, 1L, 1L),
        )
        v1.cloudMediaManifestDao().upsertAll(
            listOf(
                CloudMediaManifestEntity(
                    chatId = 100L, messageId = 1L, mediaType = "IMAGE", mimeType = "image/jpeg",
                    fileName = "a.jpg", sizeBytes = 10L, width = null, height = null, durationMs = null,
                    dateEpochSec = 1L, previewFileId = null, originalFileId = null, isDownloaded = false,
                    contentHash = null, createdAt = 1L, updatedAt = 1L,
                ),
            ),
        )
        v1.close()

        // Act: reopen the same file at version 2 with the explicit migration.
        val v2 = Room.databaseBuilder(context, TelepixDatabase::class.java, dbFile)
            .addMigrations(TelepixDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        // Assert: Phase 5 rows survived.
        assertEquals(100L, v2.cloudDestinationDao().find("telegram")?.chatId)
        assertEquals(1, v2.cloudMediaManifestDao().count())

        // Assert: the new queue table exists and works.
        assertEquals(0, kotlinx.coroutines.flow.first(v2.backupQueueDao().observeAll()).size)
        v2.close()

        context.deleteDatabase(dbFile)
    }
}
