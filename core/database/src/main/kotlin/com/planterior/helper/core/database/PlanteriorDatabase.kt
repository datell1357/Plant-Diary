package com.planterior.helper.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities =
        [
            CachedPlantEntity::class,
            CachedWateringScheduleEntity::class,
            OperationOutboxEntity::class,
            LastSyncEntity::class,
        ],
    version = 2,
    exportSchema = true,
)
abstract class PlanteriorDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    abstract fun syncDao(): SyncDao
}

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cached_plants RENAME TO cached_plants_v1")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS cached_plants (`accountId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `representativePhotoPath` TEXT, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `plantId`))"
            )
            db.execSQL(
                "INSERT INTO cached_plants (accountId, plantId, displayName, representativePhotoPath, revision, updatedAtEpochMillis) SELECT 'legacy', plantId, displayName, representativePhotoPath, revision, updatedAtEpochMillis FROM cached_plants_v1"
            )
            db.execSQL("DROP TABLE cached_plants_v1")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cached_plants_accountId ON cached_plants (`accountId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS cached_watering_schedules (`accountId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `reminderTime` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `scheduleId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cached_watering_schedules_accountId_plantId ON cached_watering_schedules (`accountId`, `plantId`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS operation_outbox (`operationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `mutationType` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `draftPayload` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `actualRevision` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`operationId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_operation_outbox_accountId_state_createdAtEpochMillis ON operation_outbox (`accountId`, `state`, `createdAtEpochMillis`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS last_sync (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`accountId`, `domain`))"
            )
        }
    }
