package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.model.AccountId
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanteriorDatabaseTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `cache is partitioned by account and switching clears prior account view`() = runTest {
        val dao = database.cacheDao()
        dao.upsertPlant(CachedPlantEntity("account-a", "plant-a", "A 몬스테라", null, 1, 1))
        dao.upsertPlant(CachedPlantEntity("account-b", "plant-b", "B 선인장", null, 1, 1))
        assertEquals(listOf("plant-a"), dao.observePlants("account-a").first().map { it.plantId })
        dao.clearVisibleAccount("account-a")
        assertTrue(dao.observePlants("account-a").first().isEmpty())
        assertEquals(listOf("plant-b"), dao.observePlants("account-b").first().map { it.plantId })
    }

    @Test
    fun `outbox is ordered and duplicate operation merges without reordering`() = runTest {
        val dao = database.syncDao()
        dao.enqueue(
            OperationOutboxEntity("op-b", "account-a", "plant", "p2", "UPDATE", 2, "draft-b", 20)
        )
        dao.enqueue(
            OperationOutboxEntity("op-a", "account-a", "plant", "p1", "UPDATE", 1, "draft-a", 10)
        )
        dao.enqueue(
            OperationOutboxEntity("op-a", "account-a", "plant", "p1", "UPDATE", 1, "draft-new", 30)
        )
        val pending = dao.pending("account-a")
        assertEquals(listOf("op-a", "op-b"), pending.map { it.operationId })
        assertEquals("draft-new", pending.first().draftPayload)
        assertEquals(10, pending.first().createdAtEpochMillis)
    }

    @Test
    fun `same operation id persists independently for two accounts`() = runTest {
        val dao = database.syncDao()
        dao.enqueue(
            OperationOutboxEntity(
                "shared-operation",
                "account-a",
                "plant",
                "p-a",
                "UPDATE",
                0,
                "draft-a",
                1,
            )
        )
        dao.enqueue(
            OperationOutboxEntity(
                "shared-operation",
                "account-b",
                "plant",
                "p-b",
                "UPDATE",
                0,
                "draft-b",
                2,
            )
        )

        assertEquals("draft-a", dao.pending("account-a").single().draftPayload)
        assertEquals("draft-b", dao.pending("account-b").single().draftPayload)
    }

    @Test
    fun `last sync and LocalDate schedule round trip exactly`() = runTest {
        val cache = database.cacheDao()
        val sync = database.syncDao()
        cache.upsertSchedule(
            CachedWateringScheduleEntity(
                "account-a",
                "schedule-a",
                "plant-a",
                "2026-08-12",
                "09:00",
                "Asia/Seoul",
                4,
                1,
            )
        )
        sync.upsertLastSync(
            LastSyncEntity("account-a", "plants", 1_786_500_000_000, "SUCCESS", null)
        )
        assertEquals(
            LocalDate.of(2026, 8, 12),
            cache.schedule("account-a", "schedule-a")?.dueLocalDate,
        )
        assertEquals(1_786_500_000_000, sync.lastSync("account-a", "plants")?.syncedAtEpochMillis)
    }

    @Test
    fun `mini home cache is partitioned by account and cleared with the account view`() = runTest {
        val dao = database.cacheDao()
        dao.upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "민지의 미니 식물원", 3, 2, 10))
        dao.upsertMiniHome(CachedMiniHomeEntity("account-b", "home-b", "다른 사람의 방", 9, 1, 20))

        assertEquals("민지의 미니 식물원", dao.miniHome("account-a")?.name)
        assertEquals(3, dao.miniHome("account-a")?.placedPlantCount)
        assertEquals("다른 사람의 방", dao.miniHome("account-b")?.name)

        dao.clearVisibleAccount("account-a")

        assertNull("계정 캐시를 비우면 미니홈피도 사라져야 한다", dao.miniHome("account-a"))
        assertEquals("다른 계정은 그대로여야 한다", "다른 사람의 방", dao.miniHome("account-b")?.name)
    }

    @Test
    fun `mini home reconcile applies remote updates and removes remote deletions`() = runTest {
        val dao = database.cacheDao()
        dao.upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "이전 이름", 1, 1, 10))

        dao.reconcileMiniHome(
            "account-a",
            CachedMiniHomeEntity("account-a", "home-a", "새 이름", 4, 2, 20),
        )
        assertEquals("새 이름", dao.miniHome("account-a")?.name)
        assertEquals(4, dao.miniHome("account-a")?.placedPlantCount)

        // 서버에서 미니홈피가 삭제되면 홈도 더 이상 이전 구성을 보여주면 안 된다.
        dao.reconcileMiniHome("account-a", null)
        assertNull(dao.miniHome("account-a"))
    }

    @Test
    fun `mini home survives reopening the database file`() = runTest {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getDatabasePath("persisted.db").path)
        file.delete()

        val first =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "persisted.db")
                .allowMainThreadQueries()
                .build()
        first
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "저장된 방", 2, 1, 30))
        first.close()

        // 프로세스가 죽었다 살아나는 경우를 흑낸다. 다시 열어도 같은 구성이 남아 있어야 한다.
        val second =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "persisted.db")
                .allowMainThreadQueries()
                .build()
        assertEquals("저장된 방", second.cacheDao().miniHome("account-a")?.name)
        assertEquals(2, second.cacheDao().miniHome("account-a")?.placedPlantCount)
        second.close()
        file.delete()

        database =
            Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @Test
    fun `migration three to four adds the mini home cache without losing rows`() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getDatabasePath("migration-3-4.db").path)
        file.delete()

        val seeded =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "migration-3-4.db")
                .allowMainThreadQueries()
                .build()
        runTest {
            seeded
                .cacheDao()
                .upsertPlant(CachedPlantEntity("account-a", "plant-a", "몬스테라", null, 1, 1))
        }
        seeded.close()

        val reopened =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "migration-3-4.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
        runTest {
            assertEquals(1, reopened.cacheDao().plants("account-a").size)
            assertNull(reopened.cacheDao().miniHome("account-a"))
            reopened
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "마이그레이션", 1, 1, 2))
            assertEquals("마이그레이션", reopened.cacheDao().miniHome("account-a")?.name)
        }
        reopened.close()
        file.delete()

        database =
            Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @Test
    fun `migration one to two preserves rows and adds account partition`() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getDatabasePath("migration.db").path)
        file.delete()
        val configuration =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration.db")
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE cached_plants (plantId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, representativePhotoPath TEXT, revision INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)"
                            )
                            db.execSQL(
                                "INSERT INTO cached_plants VALUES ('legacy-plant', 'Legacy', NULL, 1, 1)"
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
        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "migration.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
        assertEquals(
            "Legacy",
            migrated.cacheDao().plantBlocking(AccountId.LEGACY.value, "legacy-plant")?.displayName,
        )
        migrated.close()
        assertTrue(file.delete())
    }

    @Test
    fun `migration two to three partitions outbox identity by account`() = runTest {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-2-3.db"
        context.deleteDatabase(name)
        val configuration =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE cached_plants (`accountId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `representativePhotoPath` TEXT, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `plantId`))"
                            )
                            db.execSQL(
                                "CREATE INDEX index_cached_plants_accountId ON cached_plants (`accountId`)"
                            )
                            db.execSQL(
                                "CREATE TABLE cached_watering_schedules (`accountId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `reminderTime` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `scheduleId`))"
                            )
                            db.execSQL(
                                "CREATE INDEX index_cached_watering_schedules_accountId_plantId ON cached_watering_schedules (`accountId`, `plantId`)"
                            )
                            db.execSQL(
                                "CREATE TABLE operation_outbox (`operationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `mutationType` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `draftPayload` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `actualRevision` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`operationId`))"
                            )
                            db.execSQL(
                                "CREATE INDEX index_operation_outbox_accountId_state_createdAtEpochMillis ON operation_outbox (`accountId`, `state`, `createdAtEpochMillis`)"
                            )
                            db.execSQL(
                                "CREATE TABLE last_sync (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`accountId`, `domain`))"
                            )
                            db.execSQL(
                                "INSERT INTO operation_outbox VALUES ('shared-operation', 'account-a', 'plant', 'p-a', 'UPDATE', 0, 'draft-a', 1, 'PENDING', NULL, NULL)"
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
        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
        migrated
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    "shared-operation",
                    "account-b",
                    "plant",
                    "p-b",
                    "UPDATE",
                    0,
                    "draft-b",
                    2,
                )
            )
        assertEquals("draft-a", migrated.syncDao().pending("account-a").single().draftPayload)
        assertEquals("draft-b", migrated.syncDao().pending("account-b").single().draftPayload)
        migrated.close()
        assertTrue(context.deleteDatabase(name))
    }
}
