package com.planterior.helper

import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.data.INVENTORY_CONTRACT_VERSION
import com.planterior.helper.core.data.authoritativeInventorySnapshotHash
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.shop.InventoryItem
import com.planterior.helper.feature.shop.InventoryRemoteDataSource
import com.planterior.helper.feature.shop.InventorySnapshot
import com.planterior.helper.feature.shop.OwnedInventoryItem
import com.planterior.helper.feature.shop.RemoteInventoryAcquireRequest
import com.planterior.helper.feature.shop.RemoteInventoryAcquireResult

/** Inventory remote fixture used behind the production Room repository. */
internal class Todo18InventoryRepositoryFixture(private val scenario: Todo18Scenario) :
    InventoryRemoteDataSource {
    private val state = scenario.inventoryFixtureState

    override fun activeAccount(): AccountId = scenario.accountId

    override suspend fun load(accountId: AccountId): InventorySnapshot {
        require(accountId == scenario.accountId)
        scenario.emit("inventory-loaded", accountId.value)
        return state.inventorySnapshot()
    }

    override suspend fun acquire(
        request: RemoteInventoryAcquireRequest
    ): RemoteInventoryAcquireResult {
        require(request.accountId == scenario.accountId && request.itemId == state.itemId)
        state.acquire()
        scenario.emit("inventory-acquired", request.operationId.value)
        return RemoteInventoryAcquireResult.Acquired(
            scenario.accountId,
            state.itemId,
            Revision(1),
            Revision(1),
            scenario.now(),
            state.media,
        )
    }
}

internal class Todo18AuthoritativeInventoryFixtureState(private val scenario: Todo18Scenario) {
    internal val itemId = ItemId("todo18-planter")
    private val mediaHash = "a".repeat(64)
    internal val media =
        CatalogMediaIdentity(
            path = "catalog-assets/${itemId.value}/$mediaHash.webp",
            sha256 = mediaHash,
            byteSize = 1024,
            mimeType = "image/webp",
            width = 64,
            height = 64,
            mediaRevision = Revision(1),
        )
    private var owned = false
    private var generation = 1L

    internal fun inventorySnapshot(): InventorySnapshot {
        val authoritative = authoritativeInventory()
        return InventorySnapshot(
            accountId = authoritative.accountId,
            catalog = listOf(inventoryItem()),
            owned = if (owned) listOf(ownedItem()) else emptyList(),
            registeredPlantCount = authoritative.registeredPlantCount,
            loadedAt = scenario.now(),
            generation = authoritative.generation,
            snapshotHash = authoritative.snapshotHash,
        )
    }

    internal fun authoritativeInventory(): AuthoritativeInventory {
        val catalog =
            listOf(
                AuthoritativeCatalogItem(
                    itemId,
                    "Todo18 planter",
                    "Deterministic integrated journey fixture",
                    ItemCategory.DECORATION,
                    media,
                    acquisitionCondition = null,
                    revision = Revision(1),
                    updatedAtEpochMillis = scenario.now().toEpochMilli(),
                )
            )
        val ownedItems =
            if (owned) {
                listOf(
                    AuthoritativeOwnedItem(
                        itemId,
                        scenario.now().toEpochMilli(),
                        applied = false,
                        revision = Revision(1),
                        availability = AuthoritativeInventoryAvailability.AVAILABLE,
                        catalogSnapshot = null,
                    )
                )
            } else {
                emptyList()
            }
        return AuthoritativeInventory(
            contractVersion = INVENTORY_CONTRACT_VERSION,
            accountId = scenario.accountId,
            catalog = catalog,
            owned = ownedItems,
            registeredPlantCount = scenario.plants.size,
            loadedAtEpochMillis = scenario.now().toEpochMilli(),
            partial = false,
            generation = generation,
            snapshotHash =
                authoritativeInventorySnapshotHash(
                    scenario.accountId,
                    catalog,
                    ownedItems,
                    registeredPlantCount = scenario.plants.size,
                    partial = false,
                ),
        )
    }

    internal fun advanceForMiniHomeSave() {
        generation += 1
    }

    internal fun snapshotToken(): String = generation.toString(16).padStart(64, '0')

    internal fun acquire() {
        owned = true
        generation += 1
    }

    private fun inventoryItem() =
        InventoryItem(
            itemId,
            "Todo18 planter",
            "Deterministic integrated journey fixture",
            ItemCategory.DECORATION,
            media,
            acquisitionCondition = null,
            revision = Revision(1),
            updatedAt = scenario.now(),
        )

    private fun ownedItem() =
        OwnedInventoryItem(
            itemId,
            scenario.now(),
            applied = false,
            revision = Revision(1),
        )
}
