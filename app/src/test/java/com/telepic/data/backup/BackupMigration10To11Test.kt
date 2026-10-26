package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the durable pending-wait counter: a raw version-10 database — byte-for-byte what
 * [TelepicDatabase.MIGRATION_9_10] leaves behind (all eight tables, the pendingTelegram* columns,
 * every index through MIGRATION_8_9, no `pendingWaitAttempts`) holding a parked, still-sending
 * queue row — is reopened at version 11 through [TelepicDatabase.MIGRATION_10_11] alone. Room
 * validates the migrated schema on open, proving prior rows survive *and* that the new column
 * matches the entity; the counter then round-trips through the same DAO the repository drives.
 * No destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMigration10To11Test {

    @Test
    fun `version 10 rows survive and the wait counter round-trips through the dao`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_backup_wait_counter.db"
        context.deleteDatabase(dbFile)

        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 10
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
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `contentSizeBytes` INTEGER, " +
                "PRIMARY KEY(`chatId`,`messageId`))",
        )
        raw.execSQL("CREATE INDEX `index_cloud_media_manifest_contentHash` ON `cloud_media_manifest` (`contentHash`)")
        raw.execSQL("CREATE INDEX `index_cloud_media_manifest_dateEpochSec` ON `cloud_media_manifest` (`dateEpochSec`)")
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
        raw.execSQL("CREATE INDEX `index_backup_queue_createdAt` ON `backup_queue` (`createdAt`)")
        raw.execSQL("CREATE INDEX `index_backup_queue_retryCount` ON `backup_queue` (`retryCount`)")
        raw.execSQL(
            "CREATE TABLE `media_organization` (" +
                "`localMediaId` TEXT NOT NULL, " +
                "`isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                "`isArchived` INTEGER NOT NULL DEFAULT 0, " +
                "`isTrashed` INTEGER NOT NULL DEFAULT 0, " +
                "`favoriteAt` INTEGER, " +
                "`archivedAt` INTEGER, " +
                "`trashedAt` INTEGER, " +
                "`deletedFromStore` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`localMediaId`))",
        )
        raw.execSQL("CREATE INDEX `index_media_organization_isFavorite` ON `media_organization` (`isFavorite`)")
        raw.execSQL("CREATE INDEX `index_media_organization_isArchived` ON `media_organization` (`isArchived`)")
        raw.execSQL("CREATE INDEX `index_media_organization_isTrashed` ON `media_organization` (`isTrashed`)")
        raw.execSQL(
            "CREATE TABLE `media_location` (" +
                "`localMediaId` INTEGER NOT NULL, " +
                "`latitude` REAL NOT NULL, " +
                "`longitude` REAL NOT NULL, " +
                "`contentUri` TEXT NOT NULL, " +
                "`extractedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`localMediaId`))",
        )
        raw.execSQL(
            "CREATE TABLE `media_location_scan` (" +
                "`localMediaId` INTEGER NOT NULL, " +
                "`scannedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`localMediaId`))",
        )
        raw.execSQL(
            "CREATE TABLE `custom_album` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`coverMediaId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        raw.execSQL(
            "CREATE TABLE `custom_album_media` (" +
                "`albumId` INTEGER NOT NULL, " +
                "`localMediaId` INTEGER NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`albumId`, `localMediaId`), " +
                "FOREIGN KEY(`albumId`) REFERENCES `custom_album`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        raw.execSQL("CREATE INDEX `index_custom_album_media_albumId` ON `custom_album_media` (`albumId`)")
        raw.execSQL("CREATE INDEX `index_custom_album_media_localMediaId` ON `custom_album_media` (`localMediaId`)")

        raw.execSQL("INSERT INTO cloud_destination (provider, chatId, title, validated, createdAt, updatedAt) VALUES ('telegram', 100, 'Telepic Backup', 1, 1, 1)")
        raw.execSQL(
            "INSERT INTO cloud_media_manifest (chatId, messageId, mediaType, isDownloaded, contentHash, dateEpochSec, contentSizeBytes, createdAt, updatedAt) " +
                "VALUES (100, 7, 'IMAGE', 0, 'deadbeef', 5, 1000, 1, 1)",
        )
        // A row parked mid-confirmation, exactly what the wait counter is for.
        raw.execSQL(
            "INSERT INTO backup_queue (localMediaId, contentUri, mediaType, mimeType, fileName, sizeBytes, modifiedTimeSeconds, " +
                "state, retryCount, createdAt, updatedAt, telegramChatId, telegramMessageId, contentHash, contentSizeBytes, hashedAt, " +
                "pendingTelegramChatId, pendingTelegramMessageId) " +
                "VALUES ('42', 'content://m/42', 'IMAGE', 'image/jpeg', 'p42.jpg', 1000, 10, 'WAITING_FOR_NETWORK', 1, 1, 1, " +
                "100, 500, 'feedface', 1000, 9, 100, 500)",
        )
        raw.execSQL("INSERT INTO media_organization (localMediaId, isFavorite, updatedAt) VALUES ('42', 1, 1)")
        raw.execSQL("INSERT INTO media_location (localMediaId, latitude, longitude, contentUri, extractedAt) VALUES (42, 52.52, 13.405, 'content://m/42', 5)")
        raw.close()

        val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(
                // The upgrade under test: a real v10 file walking exactly one step to 11.
                TelepicDatabase.MIGRATION_10_11,
                TelepicDatabase.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()

        // Prior data survives across every table.
        val destination = db.cloudDestinationDao().find("telegram")
        assertNotNull(destination)
        // 11 -> 12 adds a nullable owner; an upgraded row is adopted by the first account, never
        // guessed during the migration itself.
        assertNull(destination?.accountId)
        val manifest = db.cloudMediaManifestDao().get(100L, 7L)
        assertEquals("deadbeef", manifest?.contentHash)
        assertEquals(1000L, manifest?.contentSizeBytes)
        val queueRow = db.backupQueueDao().findByLocalId("42")
        assertNotNull(queueRow)
        assertEquals("feedface", queueRow!!.contentHash)
        assertEquals(1, queueRow.retryCount)
        assertEquals(500L, queueRow.pendingTelegramMessageId)
        // Pre-migration rows must read as "no parked waits" — the NULL the old process-scoped map
        // started each process at, so behaviour for existing users is unchanged.
        assertNull(queueRow.pendingWaitAttempts)
        assertTrue(db.mediaOrganizationDao().get("42")?.isFavorite == true)
        assertEquals(1, db.mediaLocationDao().observeAll().first().size)

        // The column is live through the DAO the repository drives: increment, read back, reset.
        val dao = db.backupQueueDao()
        dao.setPendingWaitAttempts(queueRow.id, 7)
        assertEquals(7, dao.findByLocalId("42")!!.pendingWaitAttempts)
        dao.setPendingWaitAttempts(queueRow.id, null)
        assertNull(dao.findByLocalId("42")!!.pendingWaitAttempts)

        db.close()
        context.deleteDatabase(dbFile)
        Unit
    }
}
