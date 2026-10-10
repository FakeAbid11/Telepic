package com.telepic.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the *actual* version-5 → version-6 Room migration: a hand-built v5 database (every table
 * and index exactly as Phase 10 shipped it) with a real queue row is reopened at version 6 through
 * [TelepicDatabase.MIGRATION_5_6]. Room validates the resulting schema on open, proving the two new
 * `pendingTelegram*` columns are exactly what the entity expects; existing rows survive with the
 * new columns NULL ("no send currently unconfirmed"). No destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMigration5To6Test {

    @Test
    fun `v5 data survives and the pending-identity columns exist after 5 to 6`() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_phase_pending.db"
        context.deleteDatabase(dbFile)

        // Arrange: the exact version-5 schema (all five tables + every index) built by hand.
        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 5
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
                "`completedAt` INTEGER, `contentHash` TEXT, `contentSizeBytes` INTEGER, `hashedAt` INTEGER)",
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

        // A real v5 queue row, mid-queue with a persisted hash (the data most worth protecting).
        raw.execSQL(
            "INSERT INTO `backup_queue` (`localMediaId`, `contentUri`, `mediaType`, `mimeType`, `fileName`, " +
                "`sizeBytes`, `modifiedTimeSeconds`, `state`, `retryCount`, `lastError`, `createdAt`, `updatedAt`, " +
                "`contentHash`, `contentSizeBytes`, `hashedAt`) " +
                "VALUES ('42', 'content://media/42', 'IMAGE', 'image/jpeg', 'p.jpg', 100, 1700, 'QUEUED', 0, NULL, 1, 1, " +
                "'abc123', 100, 1)",
        )
        raw.close()

        // Act: reopen the same file at version 6 through the real migration.
        val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(TelepicDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        // Assert: the row survived verbatim and the new columns read as NULL (never fabricated).
        val row = db.backupQueueDao().findByLocalId("42")!!
        assertEquals("abc123", row.contentHash)
        assertEquals("QUEUED", row.state)
        assertNull(row.pendingTelegramChatId)
        assertNull(row.pendingTelegramMessageId)

        // Assert: the new columns are actually usable (Room wrote them with the entity-matching type).
        assertEquals(1, db.backupQueueDao().recordPendingRemote(row.id, 100L, 500L, 2L))
        val updated = db.backupQueueDao().findByLocalId("42")!!
        assertEquals(100L, updated.pendingTelegramChatId)
        assertEquals(500L, updated.pendingTelegramMessageId)
        // markBackedUp must clear the pending identity atomically with the confirmation.
        assertEquals(1, db.backupQueueDao().markBackedUp(row.id, 100L, 500L, 7, 3L))
        val done = db.backupQueueDao().findByLocalId("42")!!
        assertEquals("BACKED_UP", done.state)
        assertNull(done.pendingTelegramMessageId)

        assertEquals(1, db.backupQueueDao().observeAll().first().size)
        db.close()
        context.deleteDatabase(dbFile)
        }
    }
}
