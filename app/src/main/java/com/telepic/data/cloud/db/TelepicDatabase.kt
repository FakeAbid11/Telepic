package com.telepic.data.cloud.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.data.backup.db.BackupQueueEntity
import com.telepic.data.media.db.CustomAlbumDao
import com.telepic.data.media.db.CustomAlbumEntity
import com.telepic.data.media.db.CustomAlbumMediaEntity
import com.telepic.data.media.db.MediaLocationDao
import com.telepic.data.media.db.MediaLocationEntity
import com.telepic.data.media.db.MediaLocationScanEntity
import com.telepic.data.organization.db.MediaOrganizationDao
import com.telepic.data.organization.db.MediaOrganizationEntity

/**
 * Telepic application database (Phase 5 cloud metadata; Phase 6 backup queue; Phase 7 content-hash
 * recognition; Phase 10 favorites/archive/trash organization + geotagged-location cache; user-created
 * albums).
 *
 * Version 11 with **no destructive fallback**. Each version bump adds an explicit [Migration] so
 * Phase 5 destination/manifest rows, Phase 6 queue rows and Phase 7 hashes always survive:
 * - `1 → 2` ([MIGRATION_1_2]): introduces `backup_queue`.
 * - `2 → 3` ([MIGRATION_2_3]): adds `contentSizeBytes` + `hashedAt` and the `contentHash` indexes
 *   to `backup_queue`, and a `contentHash` index to `cloud_media_manifest`.
 * - `3 → 4` ([MIGRATION_3_4]): introduces `media_organization` (favorites / archive / trash state).
 * - `4 → 5` ([MIGRATION_4_5]): introduces `media_location` (the geotagged-location cache).
 * - `5 → 6` ([MIGRATION_5_6]): adds the `pendingTelegram*` columns to `backup_queue` (early remote
 *   identity persistence, so a timed-out send is confirmed rather than duplicated).
 * - `6 → 7` ([MIGRATION_6_7]): introduces `custom_album` + `custom_album_media` (user-created albums).
 * - `7 → 8` ([MIGRATION_7_8]): introduces `media_location_scan` (which items the map has already
 *   examined for GPS, including the ones that carry none).
 * - `8 → 9` ([MIGRATION_8_9]): adds the missing indices on the columns the queue and manifest are
 *   sorted/filtered by. Additive only — no table or column changes.
 * - `9 → 10` ([MIGRATION_9_10]): adds `cloud_media_manifest.contentSizeBytes`, the byte count a
 *   recovered content hash was computed over. Additive only.
 * - `10 → 11` ([MIGRATION_10_11]): adds `backup_queue.pendingWaitAttempts`, the durable
 *   "still sending" wait counter, so the pending-send wait cap survives process death. Additive only.
 * - `11 → 12` ([MIGRATION_11_12]): adds the nullable `cloud_destination.accountId` so a persisted
 *   channel can never be reused by a different Telegram account. Existing rows are adopted by the
 *   first authenticated account; the column is nullable so an upgrade never guesses ownership.
 */
@Database(
    entities = [
        CloudDestinationEntity::class,
        CloudMediaManifestEntity::class,
        BackupQueueEntity::class,
        MediaOrganizationEntity::class,
        MediaLocationEntity::class,
        MediaLocationScanEntity::class,
        CustomAlbumEntity::class,
        CustomAlbumMediaEntity::class,
    ],
    version = 12,
    // Schema JSON is exported to app/schemas (see the `ksp` block in app/build.gradle.kts) and
    // committed, so Room's migration checker can verify each migration against the real schema
    // instead of trusting the hand-written SQL in this file.
    exportSchema = true,
)
abstract class TelepicDatabase : RoomDatabase() {
    abstract fun cloudDestinationDao(): CloudDestinationDao
    abstract fun cloudMediaManifestDao(): CloudMediaManifestDao
    abstract fun backupQueueDao(): BackupQueueDao
    abstract fun mediaOrganizationDao(): MediaOrganizationDao
    abstract fun mediaLocationDao(): MediaLocationDao
    abstract fun customAlbumDao(): CustomAlbumDao

    companion object {
        const val NAME = "telepic.db"

        /** Phase 5 → Phase 6: adds only the `backup_queue` table + indices. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `backup_queue` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`localMediaId` TEXT NOT NULL, " +
                        "`contentUri` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`mimeType` TEXT, " +
                        "`fileName` TEXT, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`modifiedTimeSeconds` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL, " +
                        "`lastError` TEXT, " +
                        "`telegramChatId` INTEGER, " +
                        "`telegramMessageId` INTEGER, " +
                        "`telegramFileId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`startedAt` INTEGER, " +
                        "`completedAt` INTEGER, " +
                        "`contentHash` TEXT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_backup_queue_localMediaId` ON `backup_queue` (`localMediaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_queue_state` ON `backup_queue` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_queue_updatedAt` ON `backup_queue` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_queue_telegramChatId_telegramMessageId` ON `backup_queue` (`telegramChatId`, `telegramMessageId`)")
            }
        }

        /**
         * Phase 6 → Phase 7: content-hash recognition. Adds the nullable `contentSizeBytes` and
         * `hashedAt` columns and a `contentHash` index to `backup_queue`, and a `contentHash` index
         * to `cloud_media_manifest`. Existing rows (including any pre-Phase-7 hashes) are untouched.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `backup_queue` ADD COLUMN `contentSizeBytes` INTEGER")
                db.execSQL("ALTER TABLE `backup_queue` ADD COLUMN `hashedAt` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_queue_contentHash` ON `backup_queue` (`contentHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_media_manifest_contentHash` ON `cloud_media_manifest` (`contentHash`)")
            }
        }

        /**
         * Phase 9 → Phase 10: introduces `media_organization` for Favorites / Archive / Trash. A
         * brand-new table only — no existing table or row is touched, so destination, manifest and
         * queue (with hashes) are preserved verbatim.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_organization_isFavorite` ON `media_organization` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_organization_isArchived` ON `media_organization` (`isArchived`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_organization_isTrashed` ON `media_organization` (`isTrashed`)")
            }
        }

        /**
         * Phase 10 map: introduces `media_location` (the geotagged-location cache). A new table only
         * — no existing table or row is touched.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_location` (" +
                        "`localMediaId` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`contentUri` TEXT NOT NULL, " +
                        "`extractedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`localMediaId`))",
                )
            }
        }

        /**
         * Duplicate-upload fix: nullable `pendingTelegram*` columns on `backup_queue`. Purely
         * additive — every existing row (and hash) survives with the new columns NULL, meaning
         * "no send currently unconfirmed".
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `backup_queue` ADD COLUMN `pendingTelegramChatId` INTEGER")
                db.execSQL("ALTER TABLE `backup_queue` ADD COLUMN `pendingTelegramMessageId` INTEGER")
            }
        }

        /**
         * User-created albums: two brand-new tables, `custom_album` (name + cover hint) and
         * `custom_album_media` (membership, keyed `(albumId, localMediaId)`).
         *
         * Purely additive — no existing table or row is touched, so the destination, manifest, queue
         * (with hashes), organization flags and location cache all survive verbatim. The membership
         * table declares `ON DELETE CASCADE` on `albumId`, which is why deleting an album needs no
         * second statement to stay consistent.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_album` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`coverMediaId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_album_media` (" +
                        "`albumId` INTEGER NOT NULL, " +
                        "`localMediaId` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`albumId`, `localMediaId`), " +
                        "FOREIGN KEY(`albumId`) REFERENCES `custom_album`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_custom_album_media_albumId` " +
                        "ON `custom_album_media` (`albumId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_custom_album_media_localMediaId` " +
                        "ON `custom_album_media` (`localMediaId`)",
                )
            }
        }

        /**
         * Map scan memory: introduces `media_location_scan`, which records every item the map has
         * already examined for GPS — *including* items that carry no coordinate.
         *
         * A new table only, so every existing table and row survives verbatim, and `media_location`
         * keeps its original shape (`latitude`/`longitude` stay NOT NULL: it remains strictly "items
         * that have a coordinate"). Recreating that table just to make the columns nullable would
         * have been a far riskier migration for no gain — "have we looked at this file?" is a
         * different question from "does this file have a coordinate?", so it gets its own table.
         *
         * The table starts empty, which is correct: nothing has been examined yet under the new
         * scheme, so the first sweep after upgrading re-reads EXIF once for the whole library and
         * records the result. Every sweep after that skips the already-examined items for free.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_location_scan` (" +
                        "`localMediaId` INTEGER NOT NULL, " +
                        "`scannedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`localMediaId`))",
                )
            }
        }

        /**
         * Index-only migration. `backup_queue.observeAll` orders by `createdAt`,
         * `backup_queue.fetchActionable` filters on `retryCount`, and
         * `cloud_media_manifest.observeAll` orders by `dateEpochSec` — all three were unindexed, so
         * each was a full table scan plus a sort. No columns or rows are touched, which is why this
         * migration has no data assertions to make.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_queue_createdAt` ON `backup_queue` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_queue_retryCount` ON `backup_queue` (`retryCount`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_media_manifest_dateEpochSec` " +
                        "ON `cloud_media_manifest` (`dateEpochSec`)",
                )
            }
        }

        /**
         * Recognition across reinstall: adds `cloud_media_manifest.contentSizeBytes`, the byte count
         * the stored `contentHash` was computed over.
         *
         * Additive, and nullable, so every existing manifest row survives with the column NULL. That
         * NULL is meaningful rather than a gap: it means "written before this column existed", and
         * the recognition queries fall back to `sizeBytes` via `COALESCE` for exactly those rows
         * instead of refusing to match them.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_media_manifest` ADD COLUMN `contentSizeBytes` INTEGER")
            }
        }

        /**
         * Durable pending-send wait cap: adds the nullable `backup_queue.pendingWaitAttempts`
         * column — how many consecutive "still sending" confirmations the row has parked on.
         *
         * Purely additive: every existing row survives with the column NULL, meaning "no parked
         * waits". The counter previously lived in a process-scoped map inside
         * `DefaultBackupRepository`, which meant every process death silently granted a stuck send
         * another full cap of waits; on the row it is bounded across restarts, which is the whole
         * point of the cap.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `backup_queue` ADD COLUMN `pendingWaitAttempts` INTEGER")
            }
        }

        /**
         * Account scoping: adds the nullable `cloud_destination.accountId`.
         *
         * Nullable on purpose. An upgraded install has no trustworthy record of which Telegram
         * account created its existing destination, so the first authenticated account adopts the
         * row. A later mismatch is detected and the cloud state is reset for the new account.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_destination` ADD COLUMN `accountId` INTEGER")
            }
        }
    }
}
