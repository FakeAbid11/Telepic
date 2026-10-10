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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the *actual* Phase 6 → Phase 7 Room migration: a version-2 database (destination +
 * manifest + queue, as Phase 6 created it) holding real rows — including a pre-existing content
 * hash — is reopened at version 3 through [TelepicDatabase.MIGRATION_2_3]. Room validates the
 * resulting schema on open, so this proves both that Phase 6/5 data survives AND that the new
 * `hashedAt` / `contentSizeBytes` columns and `contentHash` indexes are exactly what the entities
 * expect. No destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMigration2To3Test {

    @Test
    fun `phase 6 data survives and hash recognition schema is valid after 2 to 3`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_phase7.db"
        context.deleteDatabase(dbFile)

        // Arrange: hand-build the exact Phase 6 (version 2) schema and insert real rows.
        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 2
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
        raw.execSQL(
            "CREATE TABLE `backup_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `localMediaId` TEXT NOT NULL, " +
                "`contentUri` TEXT NOT NULL, `mediaType` TEXT NOT NULL, `mimeType` TEXT, `fileName` TEXT, " +
                "`sizeBytes` INTEGER NOT NULL, `modifiedTimeSeconds` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                "`retryCount` INTEGER NOT NULL, `lastError` TEXT, `telegramChatId` INTEGER, `telegramMessageId` INTEGER, " +
                "`telegramFileId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `startedAt` INTEGER, " +
                "`completedAt` INTEGER, `contentHash` TEXT)",
        )
        raw.execSQL("CREATE UNIQUE INDEX `index_backup_queue_localMediaId` ON `backup_queue` (`localMediaId`)")
        raw.execSQL("CREATE INDEX `index_backup_queue_state` ON `backup_queue` (`state`)")
        raw.execSQL("CREATE INDEX `index_backup_queue_updatedAt` ON `backup_queue` (`updatedAt`)")
        raw.execSQL("CREATE INDEX `index_backup_queue_telegramChatId_telegramMessageId` ON `backup_queue` (`telegramChatId`, `telegramMessageId`)")

        raw.execSQL("INSERT INTO cloud_destination (provider, chatId, title, validated, createdAt, updatedAt) VALUES ('telegram', 100, 'Telepic Backup', 1, 1, 1)")
        raw.execSQL("INSERT INTO cloud_media_manifest (chatId, messageId, mediaType, sizeBytes, isDownloaded, contentHash, createdAt, updatedAt) VALUES (100, 7, 'IMAGE', 1000, 0, 'deadbeef', 1, 1)")
        raw.execSQL(
            "INSERT INTO backup_queue (localMediaId, contentUri, mediaType, sizeBytes, modifiedTimeSeconds, state, retryCount, telegramChatId, telegramMessageId, createdAt, updatedAt, contentHash) " +
                "VALUES ('42', 'content://m/42', 'IMAGE', 1000, 10, 'BACKED_UP', 2, 100, 7, 1, 1, 'feedface')",
        )
        raw.close()

        // Act: reopen at version 3 with the explicit migration (Room validates the final schema).
        val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(
                TelepicDatabase.MIGRATION_2_3,
                TelepicDatabase.MIGRATION_3_4,
                TelepicDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()

        runBlocking {
            // Assert: Phase 5 destination + manifest survive (including the reserved hash).
            val dest = db.cloudDestinationDao().find("telegram")
            assertNotNull(dest)
            assertEquals(100L, dest!!.chatId)
            assertEquals("deadbeef", db.cloudMediaManifestDao().findByContentHash("deadbeef")?.contentHash)

            // Assert: Phase 6 queue row survives with its state, retries, remote ids and hash.
            val q = db.backupQueueDao().findByLocalId("42")
            assertNotNull(q)
            assertEquals("BACKED_UP", q!!.state)
            assertEquals(2, q.retryCount)
            assertEquals(7L, q.telegramMessageId)
            assertEquals("feedface", q.contentHash)
            // New columns exist (default null on pre-migration rows).
            assertNull(q.hashedAt)
            assertNull(q.contentSizeBytes)

            // Assert: the new queue schema accepts a hash + hashedAt (recognize path).
            val freshId = db.backupQueueDao().insertIgnore(
                with(BackupMapping) {
                    com.telepic.domain.media.LocalMedia(
                        id = 99L, contentUri = android.net.Uri.parse("content://m/99"),
                        type = com.telepic.domain.media.MediaType.PHOTO, mimeType = "image/jpeg", displayName = null,
                        dateMillis = 1000L, durationMillis = null, width = 1, height = 1, sizeBytes = 10L,
                        bucketId = null, bucketName = null, relativePath = null,
                    ).toQueueEntity(1L)
                },
            )
            db.backupQueueDao().persistHash(freshId, "abc", 10L, 5L, 5L)
            assertEquals(1, db.backupQueueDao().observeAll().first().count { it.contentHash == "abc" })
        }
        db.close()
        context.deleteDatabase(dbFile)
    }
}
