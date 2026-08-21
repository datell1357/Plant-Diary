package com.planterior.helper.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class InventorySnapshotWatermarkTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `newer inventory applies while delayed older is ignored and current remains complete`() =
        runTest {
            val dao = database.cacheDao()
            assertTrue(
                dao.applyAuthoritativeInventory(write(2, "a", listOf("first")))
                    is InventoryCacheApplyResult.Applied
            )
            assertTrue(
                dao.applyAuthoritativeInventory(write(4, "b", listOf("first", "second")))
                    is InventoryCacheApplyResult.Applied
            )
            val delayed = dao.applyAuthoritativeInventory(write(3, "c", listOf("first")))

            assertTrue(delayed is InventoryCacheApplyResult.Ignored)
            assertEquals(4L, delayed.current.watermark.generation)
            assertEquals(
                listOf("first", "second"),
                delayed.current.owned.map { it.itemId }.sorted(),
            )
        }

    @Test
    fun `first authority supersedes unverified epoch once then strict ordering resumes`() =
        runTest {
            val dao = database.cacheDao()
            dao.upsertShopItems(listOf(catalog("account-a", "legacy")))
            dao.upsertInventorySnapshotWatermark(
                InventorySnapshotWatermarkEntity(
                    accountId = "account-a",
                    generation = 9,
                    snapshotHash = "0".repeat(64),
                    registeredPlantCount = 0,
                    loadedAtEpochMillis = 0,
                    partial = true,
                    verified = false,
                )
            )

            val bootstrap = dao.applyAuthoritativeInventory(write(1, "a", listOf("current")))
            assertTrue(bootstrap is InventoryCacheApplyResult.Applied)
            assertEquals(true, bootstrap.current.watermark.verified)
            assertEquals(1L, bootstrap.current.watermark.generation)
            assertEquals(listOf("current"), bootstrap.current.catalog.map { it.itemId })

            val conflict = dao.applyAuthoritativeInventory(write(1, "b", listOf("conflict")))
            assertTrue(conflict is InventoryCacheApplyResult.Conflict)
            assertEquals(listOf("current"), conflict.current.catalog.map { it.itemId })

            val newer = dao.applyAuthoritativeInventory(write(2, "c", listOf("newer")))
            assertTrue(newer is InventoryCacheApplyResult.Applied)
            val delayed = dao.applyAuthoritativeInventory(write(1, "a", listOf("current")))
            assertTrue(delayed is InventoryCacheApplyResult.Ignored)
            assertEquals(2L, delayed.current.watermark.generation)
            assertEquals(listOf("newer"), delayed.current.catalog.map { it.itemId })
        }

    @Test
    fun `same generation is idempotent only for the exact hash and content`() = runTest {
        val dao = database.cacheDao()
        val exact = write(7, "d", listOf("first"))
        dao.applyAuthoritativeInventory(exact)

        assertTrue(dao.applyAuthoritativeInventory(exact) is InventoryCacheApplyResult.Ignored)
        val hashMismatch = dao.applyAuthoritativeInventory(write(7, "e", listOf("first")))
        val contentMismatch =
            dao.applyAuthoritativeInventory(
                exact.copy(owned = listOf(owned("account-a", "different")))
            )
        assertTrue(hashMismatch is InventoryCacheApplyResult.Conflict)
        assertTrue(contentMismatch is InventoryCacheApplyResult.Conflict)
        assertEquals(listOf("first"), hashMismatch.current.owned.map { it.itemId })
    }

    @Test
    fun `watermarks survive repository recreation and remain owner isolated`() = runTest {
        val dao = database.cacheDao()
        dao.applyAuthoritativeInventory(write(5, "f", listOf("a-owned")))
        dao.applyAuthoritativeInventory(write(2, "1", listOf("b-owned"), accountId = "account-b"))

        assertEquals(5L, dao.currentInventoryCache("account-a")?.watermark?.generation)
        assertEquals(
            listOf("a-owned"),
            dao.currentInventoryCache("account-a")?.owned?.map { it.itemId },
        )
        assertEquals(2L, dao.currentInventoryCache("account-b")?.watermark?.generation)
        assertEquals(
            listOf("b-owned"),
            dao.currentInventoryCache("account-b")?.owned?.map { it.itemId },
        )
    }

    private fun write(
        generation: Long,
        hashChar: String,
        itemIds: List<String>,
        accountId: String = "account-a",
    ) =
        AuthoritativeInventoryCacheWrite(
            accountId = accountId,
            generation = generation,
            snapshotHash = hashChar.repeat(64),
            registeredPlantCount = 1,
            loadedAtEpochMillis = generation,
            partial = false,
            catalog = itemIds.map { catalog(accountId, it) },
            owned = itemIds.map { owned(accountId, it) },
        )

    private fun catalog(accountId: String, itemId: String) =
        CachedShopItemEntity(
            accountId,
            itemId,
            itemId,
            "$itemId description",
            "DECORATION",
            "catalog-assets/$itemId/preview.webp",
            null,
            1,
            1,
        )

    private fun owned(accountId: String, itemId: String) =
        CachedOwnedItemEntity(accountId, itemId, 1, false, 1)
}
