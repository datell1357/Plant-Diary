package com.planterior.helper.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.security.MessageDigest
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
class AuthoritativeInventoryCacheTest {
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
    fun `maximum exact snapshot preserves every item type identity limit revision and server time`() =
        runTest {
            val snapshot = inventory(itemCount = 200, generation = 7, loadedAt = 987_654)

            database.cacheDao().applyAuthoritativeInventory(snapshot.cacheWrite())
            val restored =
                database.cacheDao().verifiedAuthoritativeInventory(AccountId("account-a"))

            assertEquals(snapshot.snapshotHash, restored?.snapshotHash)
            assertEquals(
                snapshot.catalog.sortedBy { it.itemId.value },
                restored?.catalog?.sortedBy { it.itemId.value },
            )
            assertEquals(
                snapshot.owned.sortedBy { it.itemId.value },
                restored?.owned?.sortedBy { it.itemId.value },
            )
            assertEquals(200, restored?.catalog?.size)
            assertEquals(200, restored?.owned?.size)
            assertEquals(
                ItemCategory.entries.toSet(),
                restored?.catalog?.map { it.category }?.toSet(),
            )
            assertEquals(7L, restored?.generation)
            assertEquals(987_654L, restored?.loadedAtEpochMillis)
        }

    @Test
    fun `digest mutation fails closed and purges the exact poisoned generation`() = runTest {
        val snapshot = inventory(itemCount = 1, generation = 3, loadedAt = 30)
        database.cacheDao().applyAuthoritativeInventory(snapshot.cacheWrite())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE cached_shop_items SET assetSha256 = ? WHERE accountId = ?",
            arrayOf("f".repeat(64), "account-a"),
        )

        assertNull(database.cacheDao().verifiedAuthoritativeInventory(AccountId("account-a")))
        assertTrue(database.cacheDao().shopItems("account-a").isEmpty())
        assertTrue(database.cacheDao().ownedItems("account-a").isEmpty())
        assertNull(database.cacheDao().inventorySnapshotWatermark("account-a"))
    }

    @Test
    fun `logout or deletion clearing one owner cannot expose or erase another owner inventory`() =
        runTest {
            database.cacheDao().applyAuthoritativeInventory(inventory(2, 4, 40).cacheWrite())
            database
                .cacheDao()
                .applyAuthoritativeInventory(
                    inventory(1, 2, 20, accountId = "account-b").cacheWrite()
                )

            database.cacheDao().clearVisibleAccount("account-a")

            assertNull(database.cacheDao().verifiedAuthoritativeInventory(AccountId("account-a")))
            assertEquals(
                listOf("item-0"),
                database
                    .cacheDao()
                    .verifiedAuthoritativeInventory(AccountId("account-b"))
                    ?.catalog
                    ?.map { it.itemId.value },
            )
        }

    @Test
    fun `torn catalog rows fail closed without purging another owner`() = runTest {
        database.cacheDao().applyAuthoritativeInventory(inventory(2, 4, 40).cacheWrite())
        database
            .cacheDao()
            .applyAuthoritativeInventory(inventory(1, 2, 20, accountId = "account-b").cacheWrite())
        database.cacheDao().clearShopItems("account-a")

        assertNull(database.cacheDao().verifiedAuthoritativeInventory(AccountId("account-a")))
        val ownerB = database.cacheDao().verifiedAuthoritativeInventory(AccountId("account-b"))
        assertEquals(2L, ownerB?.generation)
        assertEquals(1, ownerB?.catalog?.size)
    }

    private fun inventory(
        itemCount: Int,
        generation: Long,
        loadedAt: Long,
        accountId: String = "account-a",
    ): AuthoritativeInventory {
        val owner = AccountId(accountId)
        val catalog =
            (0 until itemCount).map { index ->
                val itemId = "item-$index"
                AuthoritativeCatalogItem(
                    itemId = ItemId(itemId),
                    name = "Item $index",
                    description = "Description $index",
                    category = ItemCategory.entries[index % ItemCategory.entries.size],
                    mediaIdentity = identity(itemId, index),
                    acquisitionCondition =
                        AuthoritativeInventoryCondition.REGISTERED_PLANT.takeIf { index % 2 == 1 },
                    revision = Revision(generation),
                    updatedAtEpochMillis = loadedAt + index,
                )
            }
        val owned = catalog.mapIndexed { index, item ->
            AuthoritativeOwnedItem(
                itemId = item.itemId,
                acquiredAtEpochMillis = loadedAt + index,
                applied = index % 2 == 0,
                revision = Revision(generation),
                availability = AuthoritativeInventoryAvailability.AVAILABLE,
                catalogSnapshot = null,
            )
        }
        return AuthoritativeInventory(
            contractVersion = INVENTORY_CONTRACT_VERSION,
            accountId = owner,
            catalog = catalog,
            owned = owned,
            registeredPlantCount = 200,
            loadedAtEpochMillis = loadedAt,
            partial = false,
            generation = generation,
            snapshotHash =
                authoritativeInventorySnapshotHash(owner, catalog, owned, 200, partial = false),
        )
    }

    private fun identity(itemId: String, index: Int): CatalogMediaIdentity {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("payload-$index".toByteArray())
                .joinToString("") { "%02x".format(it) }
        return CatalogMediaIdentity(
            path = "catalog-assets/$itemId/$digest.webp",
            sha256 = digest,
            byteSize = 8L * 1024L * 1024L,
            mimeType = "image/webp",
            width = 768,
            height = 768,
            mediaRevision = Revision(index.toLong() + 1),
        )
    }
}
