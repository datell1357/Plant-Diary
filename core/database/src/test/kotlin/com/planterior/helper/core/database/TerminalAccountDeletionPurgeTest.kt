package com.planterior.helper.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalAccountDeletionPurgeTest {
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
    fun `terminal purge removes every row for one owner in one Room boundary and preserves another owner`() =
        runTest {
            assertEquals(TABLES.toSet(), ownerScopedTables())
            seedEveryTable("deleted-owner")
            seedEveryTable("retained-owner")

            database.terminalAccountDeletionDao().purgeOwner("deleted-owner")

            TABLES.forEach { table ->
                assertEquals("deleted owner in $table", 0, count(table, "deleted-owner"))
                assertEquals(
                    "retained owner in $table",
                    expectedRows(table),
                    count(table, "retained-owner"),
                )
            }
        }

    private suspend fun seedEveryTable(owner: String) {
        val cache = database.cacheDao()
        cache.upsertPlant(CachedPlantEntity(owner, "plant", "Plant", null, 1, 1))
        cache.upsertSchedule(
            CachedWateringScheduleEntity(
                owner,
                "schedule",
                "plant",
                "2026-08-24",
                null,
                "UTC",
                1,
                1,
            )
        )
        cache.upsertMiniHome(CachedMiniHomeEntity(owner, "home", "Home", 1, 1, 1))
        cache.upsertMiniHomePlacements(
            listOf(
                CachedMiniHomePlacementEntity(
                    owner,
                    "placement",
                    "home",
                    "plant",
                    null,
                    0.5,
                    0.5,
                    0,
                    1,
                )
            )
        )
        cache.upsertMiniHomeCacheWatermark(
            MiniHomeCacheWatermarkEntity(
                owner,
                1,
                "PRESENT",
                1,
                "home",
                "operation",
                "a".repeat(64),
                null,
                1,
                true,
            )
        )
        cache.upsertShopItems(
            listOf(
                CachedShopItemEntity(
                    owner,
                    "item",
                    "Item",
                    "Description",
                    "POT",
                    "asset",
                    null,
                    1,
                    1,
                )
            )
        )
        cache.upsertOwnedItems(listOf(CachedOwnedItemEntity(owner, "item", 1, false, 1)))
        cache.upsertInventorySnapshotWatermark(
            InventorySnapshotWatermarkEntity(owner, 1, "b".repeat(64), 1, 1, false, true)
        )
        listOf("PENDING", "COMPLETED", "FAILED").forEachIndexed { index, state ->
            database
                .inventoryDao()
                .insertOperation(
                    InventoryAcquisitionOperationEntity(
                        owner,
                        "inventory-operation-$index",
                        "item-$index",
                        1,
                        "hash-$index",
                        index.toLong(),
                        state = state,
                    )
                )
        }
        listOf("PENDING", "CONFLICT", "FAILED", "MAY_HAVE_COMMITTED", "RECONCILIATION_REQUIRED")
            .forEachIndexed { index, state ->
                database
                    .syncDao()
                    .enqueue(
                        OperationOutboxEntity(
                            "outbox-operation-$index",
                            owner,
                            "plant",
                            "plant",
                            "UPDATE",
                            1,
                            "payload",
                            index.toLong(),
                            state = state,
                        )
                    )
            }
        database.syncDao().upsertLastSync(LastSyncEntity(owner, "PLANTS", 1, "FAILED", "network"))
        database
            .analyticsEventQueueDao()
            .enqueueBounded(
                AnalyticsEventQueueEntity(
                    owner,
                    "11111111-1111-4111-8111-${owner.hashCode().toUInt().toString().padStart(12, '0')}",
                    "APP_SESSION_STARTED",
                    1,
                    1,
                ),
                expiredAtOrBeforeEpochMillis = 0,
            )
    }

    private fun count(table: String, owner: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE accountId = ?", arrayOf(owner))
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private fun ownerScopedTables(): Set<String> {
        val sqlite = database.openHelper.readableDatabase
        val tables =
            sqlite
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'"
                )
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
        return tables.filterTo(linkedSetOf()) { table ->
            sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                var ownerScoped = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(name) == "accountId") ownerScoped = true
                }
                ownerScoped
            }
        }
    }

    private fun expectedRows(table: String): Int =
        when (table) {
            "operation_outbox" -> 5
            "inventory_acquisition_operations" -> 3
            else -> 1
        }

    private companion object {
        val TABLES =
            listOf(
                "cached_plants",
                "cached_watering_schedules",
                "operation_outbox",
                "cached_mini_homes",
                "cached_mini_home_placements",
                "mini_home_cache_watermarks",
                "cached_shop_items",
                "cached_owned_items",
                "inventory_snapshot_watermarks",
                "inventory_acquisition_operations",
                "last_sync",
                "analytics_event_queue",
            )
    }
}
