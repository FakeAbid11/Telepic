package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the *actual* Phase 5 → Phase 6 Room migration, not just creation of the newest schema:
 * a database built at version 1 (cloud destination + manifest only), with real rows, is reopened at
 * the current version through the whole migration chain, and the Phase 5 data must survive while the
 * new backup_queue table becomes usable. No destructive fallback.
 *
 * The version-1 schema is written out as raw SQL rather than by aliasing the live entity classes.
 * That is load-bearing, not stylistic: a legacy database defined by the *current* entity would gain
 * every column added since, so `ALTER TABLE … ADD COLUMN` in a later migration would fail on a
 * duplicate column and the test would stop proving anything about real upgrade paths. Freezing the
 * old schema here is what makes "this migration works on a database that predates it" a real claim —
 * and it is why the manifest below has no `contentSizeBytes`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMigrationTest {

    @Test
    fun `phase 5 data survives the full migration chain and the queue is usable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_phase6.db"
        context.deleteDatabase(dbFile)

        kotlinx.coroutines.runBlocking {

        // Arrange: the exact version-1 schema (Phase 5: destination + manifest, no queue), with real
        // rows, built by hand.
        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 1
        raw.execSQL(
            "CREATE TABLE `cloud_destination` (`provider` TEXT NOT NULL, `chatId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `validated` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`provider`))",
        )
        raw.execSQL(
            "CREATE TABLE `cloud_media_manifest` (`chatId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, " +
                "`mediaType` TEXT NOT NULL, `mimeType` TEXT, `fileName` TEXT, `sizeBytes` INTEGER, " +
                "`width` INTEGER, `height` INTEGER, `durationMs` INTEGER, `dateEpochSec` INTEGER, " +
                "`previewFileId` INTEGER, `originalFileId` INTEGER, `isDownloaded` INTEGER NOT NULL, " +
                "`contentHash` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`chatId`,`messageId`))",
        )
        raw.execSQL(
            "INSERT INTO `cloud_destination` (`provider`, `chatId`, `title`, `validated`, `createdAt`, `updatedAt`) " +
                "VALUES ('telegram', 100, 'Telepic Backup', 1, 1, 1)",
        )
        raw.execSQL(
            "INSERT INTO `cloud_media_manifest` (`chatId`, `messageId`, `mediaType`, `mimeType`, `fileName`, " +
                "`sizeBytes`, `isDownloaded`, `contentHash`, `createdAt`, `updatedAt`) " +
                "VALUES (100, 1, 'IMAGE', 'image/jpeg', 'a.jpg', 10, 0, NULL, 1, 1)",
        )
        raw.close()

        // Act: reopen the same file at the current version with the full migration chain.
        val migrated = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(
                // Chained all the way to the current version: TelepicDatabase is always the latest, so
                // opening a v1 file has to walk every step. MIGRATION_1_2 is the one under test; the
                // rest are the path to the schema Room validates the result against.
                TelepicDatabase.MIGRATION_1_2,
                TelepicDatabase.MIGRATION_2_3,
                TelepicDatabase.MIGRATION_3_4,
                TelepicDatabase.MIGRATION_4_5,
                TelepicDatabase.MIGRATION_5_6,
                TelepicDatabase.MIGRATION_6_7,
                TelepicDatabase.MIGRATION_7_8,
                TelepicDatabase.MIGRATION_8_9,
                TelepicDatabase.MIGRATION_9_10,
                TelepicDatabase.MIGRATION_10_11,
                TelepicDatabase.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()

        // Assert: Phase 5 rows survived, and the column added by the last step is present and NULL —
        // which is the honest value for a row that predates it, and the one recognition's COALESCE
        // fallback exists to handle.
        assertEquals(100L, migrated.cloudDestinationDao().find("telegram")?.chatId)
        assertEquals(1, migrated.cloudMediaManifestDao().count())
        val manifestRow = migrated.cloudMediaManifestDao().get(100L, 1L)
        assertEquals(null, manifestRow?.contentSizeBytes)

        // Assert: the new queue table exists and works.
        assertEquals(0, migrated.backupQueueDao().observeAll().first().size)
        migrated.close()
        }

        context.deleteDatabase(dbFile)
    }
}
