package com.telepix.data.cloud.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.telepix.data.backup.db.BackupQueueDao
import com.telepix.data.backup.db.BackupQueueEntity

/**
 * Telepix application database (Phase 5 cloud metadata; Phase 6 backup queue; Phase 7 content-hash
 * recognition).
 *
 * Version 3 with **no destructive fallback**. Each version bump adds an explicit [Migration] so
 * Phase 5 destination/manifest rows and Phase 6 queue rows always survive:
 * - `1 → 2` ([MIGRATION_1_2]): introduces `backup_queue`.
 * - `2 → 3` ([MIGRATION_2_3]): adds `contentSizeBytes` + `hashedAt` and the `contentHash` indexes
 *   to `backup_queue`, and a `contentHash` index to `cloud_media_manifest`.
 */
@Database(
    entities = [
        CloudDestinationEntity::class,
        CloudMediaManifestEntity::class,
        BackupQueueEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class TelepixDatabase : RoomDatabase() {
    abstract fun cloudDestinationDao(): CloudDestinationDao
    abstract fun cloudMediaManifestDao(): CloudMediaManifestDao
    abstract fun backupQueueDao(): BackupQueueDao

    companion object {
        const val NAME = "telepix.db"

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
    }
}
