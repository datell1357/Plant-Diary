package com.planterior.helper.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer

@Database(
    entities =
        [
            CachedPlantEntity::class,
            CachedWateringScheduleEntity::class,
            CachedMiniHomeEntity::class,
            CachedMiniHomePlacementEntity::class,
            MiniHomeCacheWatermarkEntity::class,
            OperationOutboxEntity::class,
            LastSyncEntity::class,
        ],
    version = 14,
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

/** 물 주기 예정일은 알림 설정 없이도 존재한다. v5 행은 유지하고 알 수 없던 enabled는 null로 둔다. */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS cached_watering_schedules_v6 (`accountId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `reminderTime` TEXT, `zoneId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `enabled` INTEGER, PRIMARY KEY(`accountId`, `scheduleId`))"
            )
            db.execSQL(
                "INSERT INTO cached_watering_schedules_v6 (accountId, scheduleId, plantId, dueDate, reminderTime, zoneId, revision, updatedAtEpochMillis, enabled) SELECT accountId, scheduleId, plantId, dueDate, reminderTime, zoneId, revision, updatedAtEpochMillis, NULL FROM cached_watering_schedules"
            )
            db.execSQL("DROP TABLE cached_watering_schedules")
            db.execSQL(
                "ALTER TABLE cached_watering_schedules_v6 RENAME TO cached_watering_schedules"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cached_watering_schedules_accountId_plantId ON cached_watering_schedules (`accountId`, `plantId`)"
            )
        }
    }

/** 마지막 확정 미니홈피의 전체 좌표와 layering을 계정별로 복원한다. */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS cached_mini_home_placements (`accountId` TEXT NOT NULL, `placementId` TEXT NOT NULL, `miniHomeId` TEXT NOT NULL, `plantId` TEXT, `itemId` TEXT, `normalizedX` REAL NOT NULL, `normalizedY` REAL NOT NULL, `zIndex` INTEGER NOT NULL, `layoutRevision` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `placementId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_cached_mini_home_placements_accountId_miniHomeId_layoutRevision_zIndex ON cached_mini_home_placements (`accountId`, `miniHomeId`, `layoutRevision`, `zIndex`)"
            )
        }
    }

/** 미니홈피 저장의 불확실한 전송과 authoritative receipt를 프로세스 재시작 뒤에도 복원한다. */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN failureDetails TEXT")
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN committedOperationId TEXT")
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN committedExpectedRevision INTEGER")
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN committedRevision INTEGER")
            db.execSQL(
                "UPDATE operation_outbox SET state = 'RECONCILIATION_REQUIRED' WHERE aggregateType = 'miniHomeLayouts' AND state IN ('CONFLICT', 'FAILED') AND lastErrorCode IN ('REVISION_CONFLICT', 'OUTBOX_MISMATCH', 'UNAVAILABLE_ENTITY')"
            )
        }
    }

/** 미니홈피 canonical payload hash를 local command와 authoritative receipt 양쪽에 보존한다. */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN payloadHash TEXT")
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN committedPayloadHash TEXT")
        }
    }

/** 미니홈피 lineage를 추가하고 legacy 표시 이름을 canonical cache 경계로 복구한다. */
/** Adds an opaque persisted row generation so stale handles cannot delete ABA replacements. */
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE operation_outbox ADD COLUMN rowHandleId TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "UPDATE operation_outbox SET rowHandleId = lower(hex(randomblob(16))) WHERE rowHandleId = ''"
            )
        }
    }

/** Adds a monotonic CAS version to every persisted outbox generation. */
val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE operation_outbox ADD COLUMN rowVersion INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

/** Persists owner-scoped authoritative layout/deletion ordering across process restarts. */
val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS mini_home_cache_watermarks (`accountId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `kind` TEXT NOT NULL, `layoutRevision` INTEGER, `miniHomeId` TEXT, `operationId` TEXT, `payloadHash` TEXT, `tombstoneId` TEXT, `authoritativeAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`))"
            )
            db.execSQL(
                "INSERT INTO mini_home_cache_watermarks (accountId, generation, kind, layoutRevision, miniHomeId, operationId, payloadHash, tombstoneId, authoritativeAtEpochMillis) SELECT accountId, CASE WHEN revision > 0 THEN revision - 1 ELSE 0 END, 'PRESENT', revision, miniHomeId, NULL, NULL, NULL, updatedAtEpochMillis FROM cached_mini_homes"
            )
        }
    }

/**
 * Marks inferred pre-watermark state as unverified until one authoritative server epoch applies.
 */
val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE mini_home_cache_watermarks ADD COLUMN verified INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "UPDATE mini_home_cache_watermarks SET verified = 0 WHERE kind = 'PRESENT' AND operationId IS NULL AND payloadHash IS NULL AND tombstoneId IS NULL"
            )
        }
    }

val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN lineageId TEXT")
            db.execSQL("ALTER TABLE operation_outbox ADD COLUMN supersedesOperationId TEXT")
            db.execSQL(
                "UPDATE operation_outbox SET lineageId = operationId WHERE aggregateType = 'miniHomeLayouts'"
            )
            val legacyNames = buildList {
                db.query("SELECT accountId, name FROM cached_mini_homes").use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
            legacyNames.forEach { (accountId, legacyName) ->
                val canonicalName = recoverLegacyMiniHomeName(legacyName)
                if (canonicalName == null) {
                    db.execSQL(
                        "DELETE FROM cached_mini_home_placements WHERE accountId = ?",
                        arrayOf(accountId),
                    )
                    db.execSQL(
                        "DELETE FROM cached_mini_homes WHERE accountId = ?",
                        arrayOf(accountId),
                    )
                } else if (canonicalName != legacyName) {
                    db.execSQL(
                        "UPDATE cached_mini_homes SET name = ? WHERE accountId = ? AND name = ?",
                        arrayOf(canonicalName, accountId, legacyName),
                    )
                }
            }
        }
    }

internal fun recoverLegacyMiniHomeName(legacyName: String): String? {
    if (legacyName.isEmpty() || legacyName.hasUnpairedSurrogate()) return null
    val canonicalName = Normalizer.normalize(legacyName, Normalizer.Form.NFC)
    val codePoints = canonicalName.codePoints().toArray()
    if (
        codePoints.isEmpty() ||
            codePoints.size > 100 ||
            codePoints.first() in LEGACY_MINI_HOME_WHITE_SPACE ||
            codePoints.last() in LEGACY_MINI_HOME_WHITE_SPACE ||
            codePoints.any { it in 0x0000..0x001F || it in 0x007F..0x009F } ||
            codePoints.any(LEGACY_MINI_HOME_BIDI_CONTROLS::contains)
    ) {
        return null
    }
    return canonicalName
}

private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return true
                index += 2
            }
            Character.isLowSurrogate(character) -> return true
            else -> index += 1
        }
    }
    return false
}

private val LEGACY_MINI_HOME_WHITE_SPACE =
    setOf(
        0x0009,
        0x000A,
        0x000B,
        0x000C,
        0x000D,
        0x0020,
        0x0085,
        0x00A0,
        0x1680,
        0x2000,
        0x2001,
        0x2002,
        0x2003,
        0x2004,
        0x2005,
        0x2006,
        0x2007,
        0x2008,
        0x2009,
        0x200A,
        0x2028,
        0x2029,
        0x202F,
        0x205F,
        0x3000,
    )

private val LEGACY_MINI_HOME_BIDI_CONTROLS =
    setOf(
        0x061C,
        0x200E,
        0x200F,
        0x202A,
        0x202B,
        0x202C,
        0x202D,
        0x202E,
        0x2066,
        0x2067,
        0x2068,
        0x2069,
    )

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
