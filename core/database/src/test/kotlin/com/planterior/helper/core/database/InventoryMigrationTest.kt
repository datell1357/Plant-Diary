package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InventoryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun `version fourteen migrates to durable owner partitioned inventory without changing existing rows`() {
        val current =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            current
                .cacheDao()
                .upsertPlant(CachedPlantEntity("account-a", "plant-a", "몬몬이", null, 1, 1))
        }
        current.close()
        context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null).use { versionFourteen ->
            versionFourteen.execSQL("DROP TABLE inventory_acquisition_operations")
            versionFourteen.execSQL("DROP TABLE cached_owned_items")
            versionFourteen.execSQL("DROP TABLE cached_shop_items")
            versionFourteen.version = 14
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .addMigrations(
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                assertNotNull(migrated.cacheDao().plant("account-a", "plant-a"))
                migrated
                    .cacheDao()
                    .replaceInventorySnapshot(
                        "account-a",
                        listOf(
                            CachedShopItemEntity(
                                "account-a",
                                "item-a",
                                "햇살 벽지",
                                "방을 환하게 꾸며요.",
                                "BACKGROUND",
                                "catalog-assets/item-a/preview.webp",
                                null,
                                1,
                                10,
                            )
                        ),
                        listOf(
                            CachedOwnedItemEntity(
                                "account-a",
                                "item-a",
                                11,
                                false,
                                1,
                                "UNAVAILABLE",
                                "햇살 벽지",
                                "BACKGROUND",
                                "catalog-assets/item-a/preview.webp",
                                1,
                            )
                        ),
                    )
                migrated
                    .inventoryDao()
                    .insertOperation(
                        InventoryAcquisitionOperationEntity(
                            "account-a",
                            "inventory-operation-1",
                            "item-a",
                            1,
                            "hash",
                            12,
                        )
                    )
                assertEquals(1, migrated.cacheDao().shopItems("account-a").size)
                val ownership = migrated.cacheDao().ownedItems("account-a").single()
                assertEquals("UNAVAILABLE", ownership.availability)
                assertEquals("햇살 벽지", ownership.nameSnapshot)
                assertEquals("BACKGROUND", ownership.categorySnapshot)
                assertEquals("catalog-assets/item-a/preview.webp", ownership.assetPathSnapshot)
                assertEquals(1L, ownership.catalogRevisionSnapshot)
                assertNotNull(
                    migrated.inventoryDao().operation("account-a", "inventory-operation-1")
                )
                assertEquals(0, migrated.cacheDao().shopItems("account-b").size)
                assertEquals(0, migrated.cacheDao().ownedItems("account-b").size)
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `version fifteen completed ownership receipts become undelivered without merging pending or failed`() {
        context.deleteDatabase(DATABASE)
        val current =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .allowMainThreadQueries()
                .build()
        current.openHelper.writableDatabase
        seedLegacyInventory(current)
        current.close()
        context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null).use { versionFifteen ->
            versionFifteen.execSQL("DROP TABLE inventory_snapshot_watermarks")
            versionFifteen.execSQL(
                "DROP INDEX IF EXISTS index_inventory_acquisition_operations_accountId_feedbackDeliveryState_createdAtEpochMillis_operationId"
            )
            versionFifteen.execSQL(
                "DROP INDEX IF EXISTS index_inventory_acquisition_operations_accountId_state_createdAtEpochMillis"
            )
            versionFifteen.execSQL(
                "ALTER TABLE inventory_acquisition_operations RENAME TO inventory_acquisition_operations_v16"
            )
            versionFifteen.execSQL(
                "CREATE TABLE inventory_acquisition_operations (`accountId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `expectedCatalogRevision` INTEGER NOT NULL, `requestHash` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `result` TEXT, `lastErrorCode` TEXT, PRIMARY KEY(`accountId`, `operationId`))"
            )
            versionFifteen.execSQL(
                "CREATE INDEX index_inventory_acquisition_operations_accountId_state_createdAtEpochMillis ON inventory_acquisition_operations (`accountId`, `state`, `createdAtEpochMillis`)"
            )
            versionFifteen.execSQL(
                "INSERT INTO inventory_acquisition_operations VALUES ('account-a', 'completed-op', 'item-a', 1, 'hash-a', 1, 'COMPLETED', 'ALREADY_OWNED|account-a|item-a|1|2|1', NULL)"
            )
            versionFifteen.execSQL(
                "INSERT INTO inventory_acquisition_operations VALUES ('account-a', 'malformed-op', 'item-a', 1, 'hash-malformed', 2, 'COMPLETED', 'ACQUIRED|foreign-owner|item-a|1|2|1', NULL)"
            )
            versionFifteen.execSQL(
                "INSERT INTO inventory_acquisition_operations VALUES ('account-a', 'pending-op', 'item-b', 1, 'hash-b', 3, 'PENDING', NULL, NULL)"
            )
            versionFifteen.execSQL(
                "INSERT INTO inventory_acquisition_operations VALUES ('account-a', 'failed-op', 'item-c', 1, 'hash-c', 3, 'FAILED', NULL, 'CATALOG_CHANGED')"
            )
            versionFifteen.execSQL("DROP TABLE inventory_acquisition_operations_v16")
            versionFifteen.version = 15
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .addMigrations(
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                assertLegacyInventoryBootstraps(migrated)
                assertEquals(
                    "UNDELIVERED",
                    migrated
                        .inventoryDao()
                        .operation("account-a", "completed-op")
                        ?.feedbackDeliveryState,
                )
                assertEquals(
                    "NONE",
                    migrated
                        .inventoryDao()
                        .operation("account-a", "malformed-op")
                        ?.feedbackDeliveryState,
                )
                assertEquals(
                    "NONE",
                    migrated
                        .inventoryDao()
                        .operation("account-a", "pending-op")
                        ?.feedbackDeliveryState,
                )
                assertEquals(
                    "NONE",
                    migrated
                        .inventoryDao()
                        .operation("account-a", "failed-op")
                        ?.feedbackDeliveryState,
                )
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `version sixteen receipts migrate to unclaimed row-versioned delivery state`() {
        context.deleteDatabase(DATABASE)
        val current =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .allowMainThreadQueries()
                .build()
        current.openHelper.writableDatabase
        seedLegacyInventory(current)
        current.close()
        context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null).use { versionSixteen ->
            versionSixteen.execSQL("DROP TABLE inventory_snapshot_watermarks")
            versionSixteen.execSQL(
                "DROP INDEX IF EXISTS index_inventory_acquisition_operations_accountId_feedbackDeliveryState_createdAtEpochMillis_operationId"
            )
            versionSixteen.execSQL(
                "DROP INDEX IF EXISTS index_inventory_acquisition_operations_accountId_state_createdAtEpochMillis"
            )
            versionSixteen.execSQL(
                "ALTER TABLE inventory_acquisition_operations RENAME TO inventory_acquisition_operations_v17"
            )
            versionSixteen.execSQL(
                "CREATE TABLE inventory_acquisition_operations (`accountId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `expectedCatalogRevision` INTEGER NOT NULL, `requestHash` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `result` TEXT, `lastErrorCode` TEXT, `feedbackDeliveryState` TEXT NOT NULL DEFAULT 'NONE', `feedbackAcknowledgedAtEpochMillis` INTEGER, PRIMARY KEY(`accountId`, `operationId`))"
            )
            versionSixteen.execSQL(
                "CREATE INDEX index_inventory_acquisition_operations_accountId_state_createdAtEpochMillis ON inventory_acquisition_operations (`accountId`, `state`, `createdAtEpochMillis`)"
            )
            versionSixteen.execSQL(
                "CREATE INDEX index_inventory_acquisition_operations_accountId_feedbackDeliveryState_createdAtEpochMillis_operationId ON inventory_acquisition_operations (`accountId`, `feedbackDeliveryState`, `createdAtEpochMillis`, `operationId`)"
            )
            versionSixteen.execSQL(
                "INSERT INTO inventory_acquisition_operations VALUES ('account-a', 'claimable-op', 'item-a', 1, 'hash-a', 1, 'COMPLETED', 'ACQUIRED|account-a|item-a|1|2|1', NULL, 'UNDELIVERED', NULL)"
            )
            versionSixteen.execSQL("DROP TABLE inventory_acquisition_operations_v17")
            versionSixteen.version = 16
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .addMigrations(
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                assertLegacyInventoryBootstraps(migrated)
                val operation =
                    requireNotNull(migrated.inventoryDao().operation("account-a", "claimable-op"))
                assertEquals("UNDELIVERED", operation.feedbackDeliveryState)
                assertEquals(null, operation.feedbackClaimToken)
                assertEquals(null, operation.feedbackClaimControllerEpoch)
                assertEquals(null, operation.feedbackClaimGeneration)
                assertEquals(null, operation.feedbackClaimLeaseExpiresAtEpochMillis)
                assertEquals(0L, operation.feedbackRowVersion)
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `version seventeen path-only inventory is purged before accepting first authority`() {
        context.deleteDatabase(DATABASE)
        val current =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .allowMainThreadQueries()
                .build()
        runBlocking {
            current
                .cacheDao()
                .replaceInventorySnapshot(
                    "account-a",
                    listOf(
                        CachedShopItemEntity(
                            "account-a",
                            "legacy-item",
                            "Legacy",
                            "Legacy description",
                            "DECORATION",
                            "catalog-assets/legacy-item/preview.webp",
                            null,
                            1,
                            1,
                        )
                    ),
                    emptyList(),
                )
        }
        current.close()
        context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null).use { versionSeventeen ->
            versionSeventeen.execSQL("DROP TABLE inventory_snapshot_watermarks")
            versionSeventeen.version = 17
        }

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DATABASE)
                .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                .allowMainThreadQueries()
                .build()
        try {
            runBlocking {
                assertEquals(null, migrated.cacheDao().currentInventoryCache("account-a"))
                assertEquals(
                    emptyList<CachedShopItemEntity>(),
                    migrated.cacheDao().shopItems("account-a"),
                )

                val result =
                    migrated
                        .cacheDao()
                        .applyAuthoritativeInventory(
                            AuthoritativeInventoryCacheWrite(
                                "account-a",
                                1,
                                "a".repeat(64),
                                0,
                                2,
                                false,
                                emptyList(),
                                emptyList(),
                            )
                        )
                assertEquals(true, result is InventoryCacheApplyResult.Applied)
                assertEquals(1L, result.current.watermark.generation)
                assertEquals(true, result.current.watermark.verified)
            }
        } finally {
            migrated.close()
        }
    }

    private fun seedLegacyInventory(database: PlanteriorDatabase) = runBlocking {
        database
            .cacheDao()
            .replaceInventorySnapshot(
                "account-a",
                listOf(
                    CachedShopItemEntity(
                        "account-a",
                        "legacy-item",
                        "Legacy",
                        "Legacy description",
                        "DECORATION",
                        "catalog-assets/legacy-item/preview.webp",
                        null,
                        1,
                        1,
                    )
                ),
                emptyList(),
            )
    }

    private suspend fun assertLegacyInventoryBootstraps(database: PlanteriorDatabase) {
        assertEquals(null, database.cacheDao().currentInventoryCache("account-a"))
        assertEquals(emptyList<CachedShopItemEntity>(), database.cacheDao().shopItems("account-a"))
        val applied =
            database
                .cacheDao()
                .applyAuthoritativeInventory(
                    AuthoritativeInventoryCacheWrite(
                        "account-a",
                        1,
                        "a".repeat(64),
                        0,
                        2,
                        false,
                        emptyList(),
                        emptyList(),
                    )
                )
        assertEquals(true, applied is InventoryCacheApplyResult.Applied)
        assertEquals(true, applied.current.watermark.verified)
        assertEquals(1L, applied.current.watermark.generation)
        assertEquals(emptyList<CachedShopItemEntity>(), applied.current.catalog)
    }

    private companion object {
        const val DATABASE = "inventory-migration.db"
    }
}
