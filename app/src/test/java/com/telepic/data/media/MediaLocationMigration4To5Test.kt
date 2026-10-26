package com.telepic.data.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.media.db.MediaLocationEntity
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
 * Verifies the map migration: a version-4 database (destination + manifest + queue +
 * organization, exactly as the early Phase 10 migrations left it) holding real rows is reopened
 * at version 5 through [TelepicDatabase.MIGRATION_4_5]. Room validates the resulting schema on
 * open, proving prior data survives *and* that the new `media_location` table matches the
 * entity. No destructive fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaLocationMigration4To5Test {

    @Test
    fun `phase 10 data survives and the location table is valid after 4 to 5`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_map_phase10.db"
        context.deleteDatabase(dbFile)

        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 4
        // The version-4 schema is the version-3 schema of OrganizationMigration3To4Test plus the
        // organization table that MIGRATION_3_4 created — byte-for-byte what a real v4 file holds.
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
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_organization` (" +
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
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_media_organization_isFavorite` ON `media_organization` (`isFavorite`)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_media_organization_isArchived` ON `media_organization` (`isArchived`)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_media_organization_isTrashed` ON `media_organization` (`isTrashed`)")

        raw.execSQL("INSERT INTO cloud_destination (provider, chatId, title, validated, createdAt, updatedAt) VALUES ('telegram', 100, 'Telepic Backup', 1, 1, 1)")
        raw.execSQL("INSERT INTO cloud_media_manifest (chatId, messageId, mediaType, isDownloaded, contentHash, createdAt, updatedAt) VALUES (100, 7, 'IMAGE', 0, 'deadbeef', 1, 1)")
        raw.execSQL(
            "INSERT INTO backup_queue (localMediaId, contentUri, mediaType, sizeBytes, modifiedTimeSeconds, state, retryCount, createdAt, updatedAt, contentHash) " +
                "VALUES ('42', 'content://m/42', 'IMAGE', 1000, 10, 'BACKED_UP', 0, 1, 1, 'feedface')",
        )
        raw.execSQL("INSERT INTO media_organization (localMediaId, isFavorite, updatedAt) VALUES ('42', 1, 1)")
        raw.close()

        val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(
                // Chained to the current version: TelepicDatabase is always the latest, so opening a v4
                // file has to walk every remaining step. MIGRATION_4_5 is the one under test.
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

        // Prior data survives.
        assertNotNull(db.cloudDestinationDao().find("telegram"))
        assertEquals("deadbeef", db.cloudMediaManifestDao().get(100L, 7L)?.contentHash)
        val queueRow = db.backupQueueDao().findByLocalId("42")
        assertNotNull(queueRow)
        assertEquals("feedface", queueRow!!.contentHash)
        val organization = db.mediaOrganizationDao().get("42")
        assertNotNull(organization)
        assertTrue(organization?.isFavorite == true)

        // The new location table is functional and matches the entity (Room would have thrown on
        // open otherwise): write one cached coordinate and read it back both ways the map uses.
        db.mediaLocationDao().upsert(
            MediaLocationEntity(localMediaId = 42L, latitude = 52.52, longitude = 13.405, contentUri = "content://m/42", extractedAt = 5L),
        )
        assertTrue(db.mediaLocationDao().cachedIds().contains(42L))
        assertEquals(1, db.mediaLocationDao().observeAll().first().size)

        db.close()
        context.deleteDatabase(dbFile)
        Unit
    }
}
