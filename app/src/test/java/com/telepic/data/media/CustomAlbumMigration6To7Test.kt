package com.telepic.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.media.db.CustomAlbumEntity
import com.telepic.data.media.db.CustomAlbumMediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the *actual* version-6 → version-7 Room migration: a hand-built v6 database (every table and
 * index exactly as it shipped, including the `pendingTelegram*` columns) with real rows is reopened at
 * version 7 through [TelepicDatabase.MIGRATION_6_7].
 *
 * Room validates the resulting schema on open, which is the point: the two new tables are hand-written
 * SQL, and if their columns, types, primary key or foreign key did not match the entities exactly, this
 * is the only thing that would catch it — a mismatch would otherwise surface as a crash on the first
 * launch after an update, for users who already have data.
 *
 * The cascade is asserted too, because it is the part of the schema that is not visible in the entity:
 * `custom_album_media` declares `ON DELETE CASCADE`, so deleting an album must not be able to strand
 * membership rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CustomAlbumMigration6To7Test {

    @Test
    fun `v6 data survives and the album tables are valid after 6 to 7`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbFile = "migration_custom_album.db"
            context.deleteDatabase(dbFile)

            // Arrange: the exact version-6 schema (all five tables + every index) built by hand.
            val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
            raw.version = 6
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

            // Real v6 rows: a queue entry with a persisted hash, and an archive flag. Both are the kind
            // of data a user would notice losing.
            raw.execSQL(
                "INSERT INTO `backup_queue` (`localMediaId`, `contentUri`, `mediaType`, `mimeType`, `fileName`, " +
                    "`sizeBytes`, `modifiedTimeSeconds`, `state`, `retryCount`, `lastError`, `createdAt`, `updatedAt`, " +
                    "`contentHash`, `contentSizeBytes`, `hashedAt`) " +
                    "VALUES ('42', 'content://media/42', 'IMAGE', 'image/jpeg', 'p.jpg', 100, 1700, 'QUEUED', 0, NULL, 1, 1, " +
                    "'abc123', 100, 1)",
            )
            raw.execSQL(
                "INSERT INTO `media_organization` (`localMediaId`, `isFavorite`, `isArchived`, `isTrashed`, " +
                    "`deletedFromStore`, `updatedAt`) VALUES ('7', 1, 1, 0, 0, 5)",
            )
            raw.close()

            // Act: reopen the same file at version 7 through the real migration.
            val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
                .addMigrations(
                    TelepicDatabase.MIGRATION_6_7,
                    TelepicDatabase.MIGRATION_7_8,
                    TelepicDatabase.MIGRATION_8_9,
                )
                .allowMainThreadQueries()
                .build()

            // Assert: prior data survived verbatim.
            assertEquals("abc123", db.backupQueueDao().findByLocalId("42")!!.contentHash)
            assertTrue(db.mediaOrganizationDao().get("7")!!.isFavorite)

            // Assert: the new tables exist and are usable with the entity's own types.
            val albumId = db.customAlbumDao().insertAlbum(
                CustomAlbumEntity(name = "Trip", coverMediaId = null, createdAt = 1L, updatedAt = 1L),
            )
            db.customAlbumDao().insertMembers(listOf(CustomAlbumMediaEntity(albumId, 7L, 1L)))

            val album = db.customAlbumDao().observeAlbums().first().single()
            assertEquals("Trip", album.name)
            assertEquals(1, album.count)
            assertEquals(listOf(7L), db.customAlbumDao().mediaIds(albumId))

            // Assert: the foreign key really cascades, so deleting an album cannot strand membership.
            db.customAlbumDao().deleteAlbum(albumId)
            assertTrue(db.customAlbumDao().mediaIds(albumId).isEmpty())
            assertTrue(db.customAlbumDao().observeAlbums().first().isEmpty())

            db.close()
            context.deleteDatabase(dbFile)
        }
    }
}
