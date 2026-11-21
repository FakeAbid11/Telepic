package com.telepic.data.face

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.face.db.FaceEntity
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
 * Verifies `12 → 13`: three brand-new tables, and nothing else touched.
 *
 * A raw version-12 database — every table the backup pipeline relies on, holding real rows — is
 * reopened at version 13 through [TelepicDatabase.MIGRATION_12_13] alone. Room validates the migrated
 * schema against the compiled entity definitions on open, so this proves both that the existing tables
 * survived verbatim and that the new DDL matches the entities. No destructive fallback is registered.
 *
 * The People tables themselves are then exercised through the real DAOs, because "the DDL is accepted"
 * and "the queries are correct" are different claims and only the second one matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceMigration12To13Test {

    @Test
    fun `version 12 rows survive and the face index round-trips through the daos`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = "migration_face_index.db"
        context.deleteDatabase(dbFile)
        createVersion12Database(context, dbFile)

        val db = Room.databaseBuilder(context, TelepicDatabase::class.java, dbFile)
            .addMigrations(TelepicDatabase.MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()

        assertPriorRowsSurvive(db)
        exerciseFaceIndex(db)

        db.close()
        context.deleteDatabase(dbFile)
        Unit
    }

    /** Everything that existed before the upgrade is exactly where it was. */
    private suspend fun assertPriorRowsSurvive(db: TelepicDatabase) {
        assertEquals(7L, db.cloudDestinationDao().find("telegram")?.accountId)
        assertEquals("feedface", db.backupQueueDao().findByLocalId("42")?.contentHash)
        assertTrue(db.mediaOrganizationDao().get("42")?.isFavorite == true)

        // The three new tables start empty: an upgraded install has indexed nothing, so the People tab
        // must open on "not set up" rather than on an empty-looking gallery of people.
        assertEquals(0, db.faceDao().count())
        assertEquals(0, db.personDao().all().size)
    }

    /** The new tables accept real rows through the DAOs the repository drives. */
    private suspend fun exerciseFaceIndex(db: TelepicDatabase) {
        val personId = db.personDao().insert(
            com.telepic.data.face.db.PersonEntity(name = "Ada", createdAt = 1L),
        )
        db.faceDao().insertAll(
            listOf(
                FaceEntity(
                    localMediaId = 42L,
                    left = 0.1f,
                    top = 0.2f,
                    right = 0.5f,
                    bottom = 0.6f,
                    embedding = ByteArray(512 * 4),
                    personId = personId,
                    confidence = 0.9f,
                    detectedAt = 1L,
                ),
            ),
        )
        db.faceDao().markScanned(listOf(com.telepic.data.face.db.FaceScanEntity(42L, 1L)))

        assertEquals(1, db.faceDao().count())
        assertEquals(1, db.faceDao().scannedIds().size)
        assertEquals(listOf(personId), db.faceDao().observeCountsByPerson().first().map { it.personId })

        // A cover is chosen from the stored box, and the projection the grid uses resolves it.
        db.personDao().setCover(personId, db.faceDao().all().first().faceId)
        val cover = db.faceDao().observeCovers().first().single()
        assertEquals(personId, cover.personId)
        assertEquals(0.1f, cover.left)
    }

    /** Writes a raw version-12 database: every pre-existing table, and nothing from the People tab. */
    private fun createVersion12Database(context: Context, dbFile: String) {
        val raw = context.openOrCreateDatabase(dbFile, Context.MODE_PRIVATE, null)
        raw.version = 12
        createBackupTables(raw)
        createLocationTables(raw)
        createAlbumTables(raw)
        insertPriorRows(raw)
        raw.close()
    }

    /** `cloud_destination`, `cloud_media_manifest` and `backup_queue`, as version 12 left them. */
    private fun createBackupTables(raw: android.database.sqlite.SQLiteDatabase) {
        raw.execSQL(
            "CREATE TABLE `cloud_destination` (`provider` TEXT NOT NULL, `chatId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `validated` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `accountId` INTEGER, PRIMARY KEY(`provider`))",
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
                "`pendingTelegramChatId` INTEGER, `pendingTelegramMessageId` INTEGER, `pendingWaitAttempts` INTEGER)",
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
    }

    /** `media_location` and `media_location_scan` — the map's cache and its scan memory. */
    private fun createLocationTables(raw: android.database.sqlite.SQLiteDatabase) {
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
            "CREATE TABLE `media_location_scan` (`localMediaId` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`localMediaId`))",
        )
    }

    /** `custom_album` and its cascading membership table. */
    private fun createAlbumTables(raw: android.database.sqlite.SQLiteDatabase) {
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
    }

    /** Real rows in three tables, so "the data survived" is a claim about data and not about DDL. */
    private fun insertPriorRows(raw: android.database.sqlite.SQLiteDatabase) {
        raw.execSQL(
            "INSERT INTO cloud_destination (provider, chatId, title, validated, createdAt, updatedAt, accountId) " +
                "VALUES ('telegram', 100, 'Telepic Backup', 1, 1, 1, 7)",
        )
        raw.execSQL(
            "INSERT INTO backup_queue (localMediaId, contentUri, mediaType, sizeBytes, modifiedTimeSeconds, state, " +
                "retryCount, createdAt, updatedAt, contentHash) " +
                "VALUES ('42', 'content://m/42', 'IMAGE', 1000, 10, 'COMPLETED', 0, 1, 1, 'feedface')",
        )
        raw.execSQL("INSERT INTO media_organization (localMediaId, isFavorite, updatedAt) VALUES ('42', 1, 1)")
    }

    @Test
    fun `a face may exist without a person`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Null personId is the clusterer's "not placed yet", and must not be confused with "person 0".
        db.faceDao().insertAll(
            listOf(
                FaceEntity(
                    localMediaId = 1L,
                    left = 0f,
                    top = 0f,
                    right = 1f,
                    bottom = 1f,
                    embedding = ByteArray(512 * 4),
                    personId = null,
                    confidence = 0.5f,
                    detectedAt = 1L,
                ),
            ),
        )

        assertEquals(1, db.faceDao().unassignedFaces().size)
        // A person with no faces has no cover row, which is why the grid tolerates a null cover.
        assertNull(db.faceDao().observeCovers().first().firstOrNull())
        assertNotNull(db.faceDao().all().first())

        db.close()
        Unit
    }
}
