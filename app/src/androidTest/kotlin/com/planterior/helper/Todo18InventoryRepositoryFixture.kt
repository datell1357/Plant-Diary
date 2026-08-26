package com.planterior.helper

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
    private val itemId = ItemId("todo18-planter")
    private val mediaHash = "a".repeat(64)
    private val media =
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

    override fun activeAccount(): AccountId = scenario.accountId

    override suspend fun load(accountId: AccountId): InventorySnapshot {
        require(accountId == scenario.accountId)
        scenario.emit("inventory-loaded", accountId.value)
        val item =
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
        return InventorySnapshot(
            accountId = accountId,
            catalog = listOf(item),
            owned = if (owned) listOf(ownedItem()) else emptyList(),
            registeredPlantCount = 1,
            loadedAt = scenario.now(),
            generation = generation,
        )
    }

    override suspend fun acquire(
        request: RemoteInventoryAcquireRequest
    ): RemoteInventoryAcquireResult {
        require(request.accountId == scenario.accountId && request.itemId == itemId)
        owned = true
        generation += 1
        scenario.emit("inventory-acquired", request.operationId.value)
        return RemoteInventoryAcquireResult.Acquired(
            scenario.accountId,
            itemId,
            Revision(1),
            Revision(1),
            scenario.now(),
            media,
        )
    }

    private fun ownedItem() =
        OwnedInventoryItem(
            itemId,
            scenario.now(),
            applied = false,
            revision = Revision(1),
        )
}
