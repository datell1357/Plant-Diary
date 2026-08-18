package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WateringScheduleMigrationTest {
    @Test
    fun `migration five to six preserves due schedules and makes notification preferences nullable`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "watering-schedule-v5-v6.db"
            context.deleteDatabase(name)
            createVersionFive(context, name)

            val database =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                    .addMigrations(MIGRATION_5_6)
                    .allowMainThreadQueries()
                    .build()

            val migrated = requireNotNull(database.cacheDao().schedule("account-a", "plant-a"))
            assertEquals(LocalDate.of(2026, 8, 22), migrated.dueLocalDate)
            assertEquals("09:00", migrated.reminderTime)
            assertNull(migrated.enabled)

            database
                .cacheDao()
                .upsertSchedule(
                    CachedWateringScheduleEntity(
                        accountId = "account-a",
                        scheduleId = "plant-without-preferences",
                        plantId = "plant-without-preferences",
                        dueDate = "2026-08-23",
                        reminderTime = null,
                        zoneId = "Asia/Seoul",
                        revision = 1,
                        updatedAtEpochMillis = 2,
                        enabled = null,
                    )
                )
            val preferenceLess =
                requireNotNull(
                    database.cacheDao().schedule("account-a", "plant-without-preferences")
                )
            assertNull(preferenceLess.reminderTime)
            assertNull(preferenceLess.enabled)
            database.close()

            val file = context.getDatabasePath(name)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                )
                .use { raw ->
                    raw.rawQuery("PRAGMA table_info(`cached_watering_schedules`)", null).use {
                        cursor ->
                        val nullable = mutableMapOf<String, Boolean>()
                        while (cursor.moveToNext()) {
                            nullable[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                                cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 0
                        }
                        assertEquals(true, nullable["reminderTime"])
                        assertEquals(true, nullable["enabled"])
                    }
                }
            assertTrue(context.deleteDatabase(name))
        }

    private fun createVersionFive(context: Context, name: String) {
        val configuration =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            versionFiveSchema.forEach(db::execSQL)
                            db.execSQL(
                                "INSERT INTO cached_watering_schedules VALUES ('account-a', 'plant-a', 'plant-a', '2026-08-22', '09:00', 'Asia/Seoul', 3, 1)"
                            )
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                            )
                            db.execSQL(
                                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'de54d71bf8c195644ecb5313714c9b60')"
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase.close()
            it.close()
        }
    }

    private companion object {
        val versionFiveSchema =
            listOf(
                "CREATE TABLE IF NOT EXISTS `cached_plants` (`accountId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `representativePhotoPath` TEXT, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `contentId` TEXT, `registrationMethod` TEXT NOT NULL, `location` TEXT, `note` TEXT, `lastWateredDate` TEXT, `detailsComplete` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `plantId`))",
                "CREATE INDEX IF NOT EXISTS `index_cached_plants_accountId` ON `cached_plants` (`accountId`)",
                "CREATE TABLE IF NOT EXISTS `cached_watering_schedules` (`accountId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `reminderTime` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `scheduleId`))",
                "CREATE INDEX IF NOT EXISTS `index_cached_watering_schedules_accountId_plantId` ON `cached_watering_schedules` (`accountId`, `plantId`)",
                "CREATE TABLE IF NOT EXISTS `cached_mini_homes` (`accountId` TEXT NOT NULL, `miniHomeId` TEXT NOT NULL, `name` TEXT NOT NULL, `placedPlantCount` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`))",
                "CREATE TABLE IF NOT EXISTS `operation_outbox` (`operationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `mutationType` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `draftPayload` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `actualRevision` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`accountId`, `operationId`))",
                "CREATE INDEX IF NOT EXISTS `index_operation_outbox_accountId_state_createdAtEpochMillis` ON `operation_outbox` (`accountId`, `state`, `createdAtEpochMillis`)",
                "CREATE TABLE IF NOT EXISTS `last_sync` (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`accountId`, `domain`))",
            )
    }
}
