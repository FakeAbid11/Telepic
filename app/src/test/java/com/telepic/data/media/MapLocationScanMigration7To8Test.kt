package com.telepic.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.media.db.MediaLocationScanEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the *actual* version-7 → version-8 Room migration: a hand-built v7 database (every table and
 * index exactly as it shipped) with real rows is reopened at version 8 through
 * [TelepicDatabase.MIGRATION_7_8].
 *
 * Room validates the resulting schema on open, which is the point — `media_location_scan` is
 * hand-written SQL, and if its columns, types or primary key did not match the entity exactly, this is
 * the only thing that would catch it. A mismatch would otherwise surface as a crash on the first
 * launch after an update, for users who already have data.
 *
 * The row that must survive here is a cached coordinate: this migration exists precisely so that
 * `media_location` did **not** have to be rebuilt (its `latitude`/`longitude` stay NOT NULL), so
 * proving its contents are untouched is the assertion that matters most.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapLocationScanMigration7To8Test {

    @Test
    fun `v7 data survives and the scan table is valid after 7 to 8`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbFile = "migration_map_scan.db"
            context.deleteDatabase(dbFile)

            // Arrange: the exact version-7 schema (all seven tables + every index) built by hand.
            val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
            raw.version = 7
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

            // Real v7 rows. The cached coordinate is the one this migration must not disturb, and the
            // queued upload with its hash is the data a user would most notice losing.
            raw.execSQL(
                "INSERT INTO `media_location` (`localMediaId`, `latitude`, `longitude`, `contentUri`, `extractedAt`) " +
                    "VALUES (7, 48.85, 2.35, 'content://media/7', 11)",
            )
            raw.execSQL(
                "INSERT INTO `backup_queue` (`localMediaId`, `contentUri`, `mediaType`, `mimeType`, `fileName`, " +
                    "`sizeBytes`, `modifiedTimeSeconds`, `state`, `retryCount`, `lastError`, `createdAt`, `updatedAt`, " +
                    "`contentHash`, `contentSizeBytes`, `hashedAt`) " +
                    "VALUES ('42', 'content://media/42', 'IMAGE', 'image/jpeg', 'p.jpg', 100, 1700, 'QUEUED', 0, NULL, 1, 1, " +
                    "'abc123', 100, 1)",
            )
            raw.close()

            // Act: reopen the same file at version 8 through the real migration.
            val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
                .addMigrations(TelepicDatabase.MIGRATION_7_8)
                .allowMainThreadQueries()
                .build()

            // Assert: the cached coordinate survived verbatim — the reason this migration is additive.
            assertEquals(listOf(7L), db.mediaLocationDao().cachedIds())
            assertEquals("abc123", db.backupQueueDao().findByLocalId("42")!!.contentHash)

            // Assert: the new table exists and is usable with the entity's own types.
            db.mediaLocationDao().markScanned(
                listOf(MediaLocationScanEntity(localMediaId = 7L, scannedAt = 5L), MediaLocationScanEntity(8L, 5L)),
            )
            assertEquals(setOf(7L, 8L), db.mediaLocationDao().scannedIds().toSet())
            db.mediaLocationDao().deleteScanned(8L)
            assertEquals(listOf(7L), db.mediaLocationDao().scannedIds())

            db.close()
            context.deleteDatabase(dbFile)
        }
    }
}
