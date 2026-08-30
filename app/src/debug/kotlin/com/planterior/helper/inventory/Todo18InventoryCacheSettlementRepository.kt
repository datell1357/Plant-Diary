package com.planterior.helper.inventory

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.shop.InventoryAcquireRequest
import com.planterior.helper.feature.shop.InventoryAcquireResult
import com.planterior.helper.feature.shop.InventoryLoadResult
import com.planterior.helper.feature.shop.InventoryRepository
import com.planterior.helper.feature.shop.InventorySnapshot

data class Todo18InventoryCacheSettlement(
    val accountId: AccountId,
    val itemId: ItemId,
    val operationId: OperationId,
)

internal class Todo18InventoryCacheSettlementRepository(
    private val delegate: InventoryRepository,
    private val onSettled: (Todo18InventoryCacheSettlement) -> Unit,
    private val onAcquired: (Todo18InventoryCacheSettlement) -> Unit = {},
) : InventoryRepository by delegate {
    private val lock = Any()
    private val armed = linkedMapOf<OperationId, Todo18InventoryCacheSettlement>()

    override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
        val result = delegate.acquire(request)
        if (result is InventoryAcquireResult.Success) {
            val settlement =
                Todo18InventoryCacheSettlement(
                    request.accountId,
                    request.itemId,
                    request.operationId,
                )
            synchronized(lock) { armed[request.operationId] = settlement }
            report(settlement, onAcquired)
        }
        return result
    }

    override suspend fun load(): InventoryLoadResult {
        val result = delegate.load()
        val snapshot = result.authoritativeSnapshot() ?: return result
        val settled =
            synchronized(lock) {
                armed.values
                    .filter { settlement -> snapshot.settles(settlement) }
                    .onEach { settlement -> armed.remove(settlement.operationId) }
            }
        settled.forEach { report(it, onSettled) }
        return result
    }

    private fun report(
        settlement: Todo18InventoryCacheSettlement,
        observer: (Todo18InventoryCacheSettlement) -> Unit,
    ) {
        try {
            observer(settlement)
        } catch (_: AssertionError) {
            return
        } catch (_: Exception) {
            return
        }
    }

    private fun InventoryLoadResult.authoritativeSnapshot(): InventorySnapshot? =
        when (this) {
            is InventoryLoadResult.Ready -> snapshot.takeUnless { stale }
            is InventoryLoadResult.Partial -> snapshot.takeUnless { stale }
            InventoryLoadResult.Failed,
            InventoryLoadResult.Forbidden -> null
        }

    private fun InventorySnapshot.settles(settlement: Todo18InventoryCacheSettlement): Boolean =
        accountId == settlement.accountId && owned.any { it.itemId == settlement.itemId }
}
