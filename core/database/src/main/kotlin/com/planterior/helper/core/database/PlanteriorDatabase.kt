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
            CachedMiniHomeEntity::class,
            OperationOutboxEntity::class,
            LastSyncEntity::class,
        ],
    version = 5,
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

/** 홈 미니홈피 미리보기를 위한 계정별 캐시를 추가한다. 기존 행은 건드리지 않는다. */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS cached_mini_homes (`accountId` TEXT NOT NULL, `miniHomeId` TEXT NOT NULL, `name` TEXT NOT NULL, `placedPlantCount` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`))"
            )
        }
    }

/** 상세의 본인 소유 관리 기록을 오프라인에서도 복원하기 위해 기존 식물 캐시를 확장한다. */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cached_plants ADD COLUMN contentId TEXT")
            db.execSQL(
                "ALTER TABLE cached_plants ADD COLUMN registrationMethod TEXT NOT NULL DEFAULT 'MANUAL'"
            )
            db.execSQL("ALTER TABLE cached_plants ADD COLUMN location TEXT")
            db.execSQL("ALTER TABLE cached_plants ADD COLUMN note TEXT")
            db.execSQL("ALTER TABLE cached_plants ADD COLUMN lastWateredDate TEXT")
            db.execSQL(
                "ALTER TABLE cached_plants ADD COLUMN detailsComplete INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DROP INDEX IF EXISTS index_operation_outbox_accountId_state_createdAtEpochMillis"
            )
            db.execSQL("ALTER TABLE operation_outbox RENAME TO operation_outbox_v2")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS operation_outbox (`operationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `mutationType` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `draftPayload` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `actualRevision` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`accountId`, `operationId`))"
            )
            db.execSQL(
                "INSERT INTO operation_outbox SELECT operationId, accountId, aggregateType, aggregateId, mutationType, expectedRevision, draftPayload, createdAtEpochMillis, state, actualRevision, lastErrorCode FROM operation_outbox_v2"
            )
            db.execSQL("DROP TABLE operation_outbox_v2")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_operation_outbox_accountId_state_createdAtEpochMillis ON operation_outbox (`accountId`, `state`, `createdAtEpochMillis`)"
            )
        }
    }
