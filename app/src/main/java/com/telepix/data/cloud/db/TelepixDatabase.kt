package com.telepix.data.cloud.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Telepix application database (introduced in Phase 5 for persistent cloud metadata).
 *
 * Version 1 with an explicit schema and **no destructive fallback**: future versions must add
 * real [androidx.room.migration.Migration]s rather than `fallbackToDestructiveMigration`.
 */
@Database(
    entities = [CloudDestinationEntity::class, CloudMediaManifestEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TelepixDatabase : RoomDatabase() {
    abstract fun cloudDestinationDao(): CloudDestinationDao
    abstract fun cloudMediaManifestDao(): CloudMediaManifestDao

    companion object {
        const val NAME = "telepix.db"
    }
}
