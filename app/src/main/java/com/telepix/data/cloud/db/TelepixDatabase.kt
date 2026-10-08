package com.telepix.data.cloud.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.telepix.data.backup.db.BackupQueueDao
import com.telepix.data.backup.db.BackupQueueEntity

/**
 * Telepix application database (introduced in Phase 5 for persistent cloud metadata; extended in
 * Phase 6 with the persistent backup queue).
 *
 * Version 2 with **no destructive fallback**: Phase 5's `cloud_destination` and
 * `cloud_media_manifest` data survives the explicit [MIGRATION_1_2], which only adds the new
 * `backup_queue` table and its indices. Future versions must keep adding real migrations.
 */
@Database(
    entities = [
        CloudDestinationEntity::class,
        CloudMediaManifestEntity::class,
        BackupQueueEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TelepixDatabase : RoomDatabase() {
    abstract fun cloudDestinationDao(): CloudDestinationDao
    abstract fun cloudMediaManifestDao(): CloudMediaManifestDao
    abstract fun backupQueueDao(): BackupQueueDao

    companion object {
        const val NAME = "telepix.db"

        /**
         * Explicit 1 → 2 migration: adds only the `backup_queue` table + indices. Written to match
         * Room's own schema for [BackupQueueEntity] exactly, so it is verified against a real
         * version-1 database by the migration test.
         */
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
    }
}
