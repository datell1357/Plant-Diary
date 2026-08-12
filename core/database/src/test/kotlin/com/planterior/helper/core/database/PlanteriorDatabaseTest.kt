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
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
        assertEquals(
            "Legacy",
            migrated.cacheDao().plantBlocking(AccountId.LEGACY.value, "legacy-plant")?.displayName,
        )
        migrated.close()
        assertTrue(file.delete())
    }
}
