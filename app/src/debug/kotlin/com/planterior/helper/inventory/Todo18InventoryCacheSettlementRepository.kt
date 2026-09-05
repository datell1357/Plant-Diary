package com.planterior.helper.inventory

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.shop.InventoryAcquireRequest
import com.planterior.helper.feature.shop.InventoryAcquireResult
import com.planterior.helper.feature.shop.InventoryFeedback
import com.planterior.helper.feature.shop.InventoryLoadResult
import com.planterior.helper.feature.shop.InventoryRepository
import com.planterior.helper.feature.shop.InventorySnapshot

data class Todo18InventoryCacheSettlement(
    val accountId: AccountId,
    val itemId: ItemId,
    val operationId: OperationId,
)

internal enum class Todo18InventorySettlementStage {
    ACQUIRE_RESULT,
    ACQUISITION_ARMED,
    LOAD_ENTERED,
    LOAD_RETURNED,
    SETTLEMENT_ELIGIBILITY,
    SETTLEMENT_ATTEMPT,
    SETTLEMENT_EMISSION,
    BOUNDARY_DELIVERY,
    RENDERED_FEEDBACK,
}

internal data class Todo18InventorySettlementObservation(
    val stage: Todo18InventorySettlementStage,
    val settlement: Todo18InventoryCacheSettlement,
    val loadKind: String? = null,
    val stale: Boolean? = null,
    val ownedItemIds: List<ItemId>? = null,
    val eligible: Boolean? = null,
    val feedback: InventoryFeedback? = null,
)

internal object Todo18InventorySettlementReceiptReducer {
    fun problems(observations: List<Todo18InventorySettlementObservation>): List<String> {
        val problems = mutableListOf<String>()
        if (observations.map { it.stage } != Todo18InventorySettlementStage.entries) {
            problems += "inventory-settlement-missing-duplicate-or-out-of-order"
        }
        if (observations.map { it.settlement }.distinct().size != 1) {
            problems += "inventory-settlement-identity-mismatch"
        }
        val returned = observations.singleOrNull {
            it.stage == Todo18InventorySettlementStage.LOAD_RETURNED
        }
        if (
            returned == null ||
                returned.loadKind !in setOf("Ready", "Partial") ||
                returned.stale != false ||
                returned.settlement.itemId !in returned.ownedItemIds.orEmpty()
        ) {
            problems += "inventory-settlement-non-authoritative-load"
        }
        val eligibility = observations.singleOrNull {
            it.stage == Todo18InventorySettlementStage.SETTLEMENT_ELIGIBILITY
        }
        if (eligibility?.eligible != true) {
            problems += "inventory-settlement-ineligible"
        }
        val rendered = observations.singleOrNull {
            it.stage == Todo18InventorySettlementStage.RENDERED_FEEDBACK
        }
        if (
            rendered == null ||
                rendered.feedback != InventoryFeedback.ACQUIRED ||
                rendered.stale != false ||
                rendered.settlement.itemId !in rendered.ownedItemIds.orEmpty()
        ) {
            problems += "inventory-settlement-rendered-facts-mismatch"
        }
        return problems
    }
}

internal class Todo18InventorySettlementDiagnosticRecorder {
    private val lock = Any()
    private val observations = mutableListOf<Todo18InventorySettlementObservation>()

    fun record(observation: Todo18InventorySettlementObservation) {
        synchronized(lock) { observations += observation }
    }

    fun snapshot(): List<Todo18InventorySettlementObservation> =
        synchronized(lock) { observations.toList() }
}

internal class Todo18InventoryCacheSettlementRepository(
    private val delegate: InventoryRepository,
    private val onSettled: (Todo18InventoryCacheSettlement) -> Unit,
    private val onAcquired: (Todo18InventoryCacheSettlement) -> Unit = {},
    private val diagnostics: Todo18InventorySettlementDiagnosticRecorder? = null,
) : InventoryRepository by delegate {
    private val lock = Any()
    private val armed = linkedMapOf<OperationId, Todo18InventoryCacheSettlement>()

    override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
        val result = delegate.acquire(request)
        val settlement =
            Todo18InventoryCacheSettlement(request.accountId, request.itemId, request.operationId)
        record(Todo18InventorySettlementStage.ACQUIRE_RESULT, settlement)
        if (result is InventoryAcquireResult.Success) {
            synchronized(lock) { armed[request.operationId] = settlement }
            record(Todo18InventorySettlementStage.ACQUISITION_ARMED, settlement)
            report(settlement, onAcquired)
        }
        return result
    }

    override suspend fun load(): InventoryLoadResult {
        return load(forceRefresh = false)
    }

    override suspend fun load(forceRefresh: Boolean): InventoryLoadResult {
        val candidates = synchronized(lock) { armed.values.toList() }
        candidates.forEach { record(Todo18InventorySettlementStage.LOAD_ENTERED, it) }
        val result = delegate.load(forceRefresh)
        candidates.forEach { settlement ->
            record(
                Todo18InventorySettlementStage.LOAD_RETURNED,
                settlement,
                loadKind = result.kind(),
                stale = result.stale(),
                ownedItemIds = result.snapshot()?.owned?.map { it.itemId },
            )
        }
        val snapshot = result.authoritativeSnapshot()
        candidates.forEach { settlement ->
            record(
                Todo18InventorySettlementStage.SETTLEMENT_ELIGIBILITY,
                settlement,
                eligible = snapshot?.settles(settlement) == true,
            )
        }
        snapshot ?: return result
        val settled =
            synchronized(lock) {
                armed.values
                    .filter { settlement -> snapshot.settles(settlement) }
                    .onEach { settlement -> armed.remove(settlement.operationId) }
            }
        settled.forEach {
            record(Todo18InventorySettlementStage.SETTLEMENT_ATTEMPT, it)
            record(Todo18InventorySettlementStage.SETTLEMENT_EMISSION, it)
            report(it, onSettled)
        }
        return result
    }

    private fun record(
        stage: Todo18InventorySettlementStage,
        settlement: Todo18InventoryCacheSettlement,
        loadKind: String? = null,
        stale: Boolean? = null,
        ownedItemIds: List<ItemId>? = null,
        eligible: Boolean? = null,
    ) {
        try {
            diagnostics?.record(
                Todo18InventorySettlementObservation(
                    stage,
                    settlement,
                    loadKind,
                    stale,
                    ownedItemIds,
                    eligible,
                    feedback = null,
                )
            )
        } catch (_: AssertionError) {
            return
        } catch (_: Exception) {
            return
        }
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

    private fun InventoryLoadResult.snapshot(): InventorySnapshot? =
        when (this) {
            is InventoryLoadResult.Ready -> snapshot
            is InventoryLoadResult.Partial -> snapshot
            InventoryLoadResult.Failed,
            InventoryLoadResult.Forbidden -> null
        }

    private fun InventoryLoadResult.kind(): String = javaClass.simpleName

    private fun InventoryLoadResult.stale(): Boolean? =
        when (this) {
            is InventoryLoadResult.Ready -> stale
            is InventoryLoadResult.Partial -> stale
            InventoryLoadResult.Failed,
            InventoryLoadResult.Forbidden -> null
        }

    private fun InventorySnapshot.settles(settlement: Todo18InventoryCacheSettlement): Boolean =
        accountId == settlement.accountId && owned.any { it.itemId == settlement.itemId }
}
