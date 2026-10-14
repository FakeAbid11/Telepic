package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the version-8 → version-9 Room migration: a hand-built v8 database (every table and index
 * exactly as it shipped, including the v8 `media_location_scan` table) is reopened at version 9
 * through [TelepicDatabase.MIGRATION_8_9].
 *
 * The migration is index-only, so the assertions are the two things an index-only migration can get
 * wrong: the three indices must actually exist afterwards, and no existing row may be disturbed.
 * Room validates the full expected schema when it opens the migrated file, so a missing or
 * misnamed index fails this test even before the explicit `sqlite_master` check — which is the
 * point, since a schema mismatch would otherwise surface as a crash on the first launch after an
 * update for users who already have data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IndexMigration8To9Test {

    @Test
    fun `v8 data survives and the three new indices exist after 8 to 9`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbFile = "migration_indices.db"
            context.deleteDatabase(dbFile)

            // Arrange: the exact version-8 schema (all eight tables + the v8 index set) built by hand.
            val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
            raw.version = 8
            raw.execSQL(
                "CREATE TABLE `cloud_destination` (`provider` TEXT NOT NULL, `chatId` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, `validated` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`provider`))",
            )
            raw.execSQL(
                "CREATE TABLE `cloud_media_manifest` (`chatId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, " +
                    "`mediaType` TEXT NOT NULL, `mimeType` TEXT, `fileName` TEXT, `sizeBytes` INTEGER, `width` INTEGER, " +
                    "`height` INTEGER, `durationMs` INTEGER, `dateEpochSec` INTEGER, `previewFileId` INTEGER, " +
                    "`originalFileId` INTEGER, `isDownloaded` INTEGER NOT NULL, `contentHash` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`,`messageId`))",
            )
            raw.execSQL("CREATE INDEX `index_cloud_media_manifest_contentHash` ON `cloud_media_manifest` (`contentHash`)")
            raw.execSQL(
                "CREATE TABLE `backup_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `localMediaId` TEXT NOT NULL, " +
                    "`contentUri` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `mimeType` TEXT, `fileName` TEXT, " +
                    "`sizeBytes` INTEGER NOT NULL, `modifiedTimeSeconds` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`retryCount` INTEGER NOT NULL, `lastError` TEXT, `telegramChatId` INTEGER, `telegramMessageId` INTEGER, " +
                    "`telegramFileId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `startedAt` INTEGER, " +
                    "`completedAt` INTEGER, `contentHash` TEXT, `contentSizeBytes` INTEGER, `hashedAt` INTEGER, " +
                    "`pendingTelegramChatId` INTEGER, `pendingTelegramMessageId` INTEGER)",
            )
            raw.execSQL("CREATE UNIQUE INDEX `index_backup_queue_localMediaId` ON `backup_queue` (`localMediaId`)")
            raw.execSQL("CREATE INDEX `index_backup_queue_state` ON `backup_queue` (`state`)")
            raw.execSQL("CREATE INDEX `index_backup_queue_updatedAt` ON `backup_queue` (`updatedAt`)")
            raw.execSQL("CREATE INDEX `index_backup_queue_telegramChatId_telegramMessageId` ON `backup_queue` (`telegramChatId`, `telegramMessageId`)")
            raw.execSQL("CREATE INDEX `index_backup_queue_contentHash` ON `backup_queue` (`contentHash`)")
            raw.execSQL(
                "CREATE TABLE `media_organization` (`localMediaId` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                    "`isArchived` INTEGER NOT NULL DEFAULT 0, `isTrashed` INTEGER NOT NULL DEFAULT 0, `favoriteAt` INTEGER, " +
                    "`archivedAt` INTEGER, `trashedAt` INTEGER, `deletedFromStore` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`localMediaId`))",
            )
            raw.execSQL("CREATE INDEX `index_media_organization_isFavorite` ON `media_organization` (`isFavorite`)")
            raw.execSQL("CREATE INDEX `index_media_organization_isArchived` ON `media_organization` (`isArchived`)")
            raw.execSQL("CREATE INDEX `index_media_organization_isTrashed` ON `media_organization` (`isTrashed`)")
            raw.execSQL(
                "CREATE TABLE `media_location` (`localMediaId` INTEGER NOT NULL, `latitude` REAL NOT NULL, " +
                    "`longitude` REAL NOT NULL, `contentUri` TEXT NOT NULL, `extractedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`localMediaId`))",
            )
            raw.execSQL(
                "CREATE TABLE `media_location_scan` (`localMediaId` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`localMediaId`))",
            )
            raw.execSQL(
                "CREATE TABLE `custom_album` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`coverMediaId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            raw.execSQL(
                "CREATE TABLE `custom_album_media` (`albumId` INTEGER NOT NULL, `localMediaId` INTEGER NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `localMediaId`), " +
                    "FOREIGN KEY(`albumId`) REFERENCES `custom_album`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            raw.execSQL("CREATE INDEX `index_custom_album_media_albumId` ON `custom_album_media` (`albumId`)")
            raw.execSQL("CREATE INDEX `index_custom_album_media_localMediaId` ON `custom_album_media` (`localMediaId`)")

            // Real v8 rows: the queued upload is the data a user would most notice losing, and it is
            // also what the two new `backup_queue` indices are built over.
            raw.execSQL(
                "INSERT INTO `backup_queue` (`localMediaId`, `contentUri`, `mediaType`, `mimeType`, `fileName`, " +
                    "`sizeBytes`, `modifiedTimeSeconds`, `state`, `retryCount`, `lastError`, `createdAt`, `updatedAt`, " +
                    "`contentHash`, `contentSizeBytes`, `hashedAt`) " +
                    "VALUES ('42', 'content://media/42', 'IMAGE', 'image/jpeg', 'p.jpg', 100, 1700, 'QUEUED', 0, NULL, 1, 1, " +
                    "'abc123', 100, 1)",
            )
            raw.execSQL(
                "INSERT INTO `cloud_media_manifest` (`chatId`, `messageId`, `mediaType`, `isDownloaded`, " +
                    "`dateEpochSec`, `contentHash`, `createdAt`, `updatedAt`) " +
                    "VALUES (7, 9, 'PHOTO', 0, 1700, 'abc123', 1, 1)",
            )
            raw.close()

            // Act: reopen the same file at version 9 through the real migration.
            val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
                .addMigrations(TelepicDatabase.MIGRATION_8_9)
                .allowMainThreadQueries()
                .build()

            // Assert: opening at all proves Room's own schema validation accepted the result — the
            // three new indices must be present and named exactly as the entities declare them.
            val expected = setOf(
                "index_backup_queue_createdAt",
                "index_backup_queue_retryCount",
                "index_cloud_media_manifest_dateEpochSec",
            )
            val created = mutableSetOf<String>()
            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN (?, ?, ?)",
                expected.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) created += cursor.getString(0)
            }
            assertEquals(expected, created)

            // Assert: nothing was rewritten on the way through.
            assertEquals("abc123", db.backupQueueDao().findByLocalId("42")!!.contentHash)

            db.close()
            context.deleteDatabase(dbFile)
        }
    }
}
