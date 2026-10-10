package com.telepic.data.organization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the real Phase 9 → Phase 10 migration: a version-3 database (destination + manifest +
 * queue, exactly as Phase 7 left it) holding real rows is reopened at version 4 through
 * [TelepicDatabase.MIGRATION_3_4]. Room validates the resulting schema on open, proving prior data
 * survives *and* that the new `media_organization` table matches the entity. No destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrganizationMigration3To4Test {

    @Test
    fun `phase 5-7 data survives and the organization table is valid after 3 to 4`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_phase10.db"
        context.deleteDatabase(dbFile)

        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 3
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
            "CREATE INDEX `index_cloud_media_manifest_contentHash` ON `cloud_media_manifest` (`contentHash`)",
        )
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

        raw.execSQL("INSERT INTO cloud_destination (provider, chatId, title, validated, createdAt, updatedAt) VALUES ('telegram', 100, 'Telepic Backup', 1, 1, 1)")
        raw.execSQL("INSERT INTO cloud_media_manifest (chatId, messageId, mediaType, isDownloaded, contentHash, createdAt, updatedAt) VALUES (100, 7, 'IMAGE', 0, 'deadbeef', 1, 1)")
        raw.execSQL(
            "INSERT INTO backup_queue (localMediaId, contentUri, mediaType, sizeBytes, modifiedTimeSeconds, state, retryCount, createdAt, updatedAt, contentHash) " +
                "VALUES ('42', 'content://m/42', 'IMAGE', 1000, 10, 'BACKED_UP', 0, 1, 1, 'feedface')",
        )
        raw.close()

        val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(
                TelepicDatabase.MIGRATION_3_4,
                TelepicDatabase.MIGRATION_4_5,
                TelepicDatabase.MIGRATION_5_6,
            )
            .allowMainThreadQueries()
            .build()

        // Prior data survives.
        assertNotNull(db.cloudDestinationDao().find("telegram"))
        assertEquals("deadbeef", db.cloudMediaManifestDao().findByContentHash("deadbeef")?.contentHash)
        val queueRow = db.backupQueueDao().findByLocalId("42")
        assertNotNull(queueRow)
        assertEquals("feedface", queueRow!!.contentHash)

        // The new organization table is functional and matches the entity.
        val organization = DefaultMediaOrganizationRepository(db.mediaOrganizationDao(), clock = { 5L })
        organization.setFavorite(42L, true)
        organization.moveToTrash(9L)
        assertTrue(organization.favoriteIds.first().contains(42L))
        assertTrue(organization.hiddenIds().contains(9L))

        db.close()
        context.deleteDatabase(dbFile)
        Unit
    }
}
