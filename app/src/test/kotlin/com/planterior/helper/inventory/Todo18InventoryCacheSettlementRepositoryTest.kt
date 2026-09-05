package com.planterior.helper.inventory

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.shop.InventoryAcquireRequest
import com.planterior.helper.feature.shop.InventoryAcquireResult
import com.planterior.helper.feature.shop.InventoryItem
import com.planterior.helper.feature.shop.InventoryLoadResult
import com.planterior.helper.feature.shop.InventoryOwnershipReceipt
import com.planterior.helper.feature.shop.InventoryReceiptClaim
import com.planterior.helper.feature.shop.InventoryReceiptPresentationResult
import com.planterior.helper.feature.shop.InventoryRepository
import com.planterior.helper.feature.shop.InventorySnapshot
import com.planterior.helper.feature.shop.OwnedInventoryItem
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Todo18InventoryCacheSettlementRepositoryTest {
    @Test
    fun `load force refresh is forwarded while settlement still occurs once`() = runTest {
        val initial = ready(snapshot(OWNER, OTHER_ITEM), stale = false)
        val authoritative = ready(snapshot(OWNER, ITEM), stale = false)
        val delegate =
            Delegate(loadResults = ArrayDeque(listOf(initial, authoritative, authoritative)))
        val events = mutableListOf<Todo18InventoryCacheSettlement>()
        val diagnostics = Todo18InventorySettlementDiagnosticRecorder()
        val repository =
            Todo18InventoryCacheSettlementRepository(
                delegate,
                events::add,
                diagnostics = diagnostics,
            )

        repository.load()
        repository.acquire(request())
        assertSame(authoritative, repository.load(forceRefresh = true))
        assertSame(authoritative, repository.load(forceRefresh = true))
        assertEquals(listOf(false, true, true), delegate.loadForceRefreshes)
        assertEquals(listOf(settlement()), events)
        assertEquals(
            listOf(
                Todo18InventorySettlementStage.ACQUIRE_RESULT,
                Todo18InventorySettlementStage.ACQUISITION_ARMED,
                Todo18InventorySettlementStage.LOAD_ENTERED,
                Todo18InventorySettlementStage.LOAD_RETURNED,
                Todo18InventorySettlementStage.SETTLEMENT_ELIGIBILITY,
                Todo18InventorySettlementStage.SETTLEMENT_ATTEMPT,
                Todo18InventorySettlementStage.SETTLEMENT_EMISSION,
            ),
            diagnostics.snapshot().map { it.stage },
        )
    }

    @Test
    fun `forced stale load keeps settlement armed until forced authoritative load`() = runTest {
        val stale = ready(snapshot(OWNER, ITEM), stale = true)
        val authoritative = ready(snapshot(OWNER, ITEM), stale = false)
        val delegate = Delegate(loadResults = ArrayDeque(listOf(stale, authoritative)))
        val events = mutableListOf<Todo18InventoryCacheSettlement>()
        val repository = Todo18InventoryCacheSettlementRepository(delegate, events::add)

        repository.acquire(request())
        assertSame(stale, repository.load(forceRefresh = true))
        assertEquals(emptyList<Todo18InventoryCacheSettlement>(), events)
        assertSame(authoritative, repository.load(forceRefresh = true))
        assertEquals(listOf(settlement()), events)
        assertEquals(listOf(true, true), delegate.loadForceRefreshes)
    }

    @Test
    fun `initial cache and successful acquire do not settle until subsequent authoritative ready`() =
        runTest {
            val initial = ready(snapshot(OWNER, ITEM), stale = false)
            val acquired = success()
            val followUp = ready(snapshot(OWNER, ITEM), stale = false)
            val delegate = Delegate(loadResults = ArrayDeque(listOf(initial, followUp, followUp)))
            delegate.acquireResult = acquired
            val events = mutableListOf<Todo18InventoryCacheSettlement>()
            val repository = Todo18InventoryCacheSettlementRepository(delegate, events::add)

            assertSame(initial, repository.load())
            assertEquals(emptyList<Todo18InventoryCacheSettlement>(), events)
            assertSame(acquired, repository.acquire(request()))
            assertEquals(emptyList<Todo18InventoryCacheSettlement>(), events)
            assertSame(followUp, repository.load())
            assertEquals(listOf(settlement()), events)
            assertSame(followUp, repository.load())
            assertEquals(listOf(settlement()), events)
        }

    @Test
    fun `authoritative ready and partial each settle exact identity once`() = runTest {
        listOf<(InventorySnapshot) -> InventoryLoadResult>(
                { ready(it, stale = false) },
                { InventoryLoadResult.Partial(it, stale = false) },
            )
            .forEach { result ->
                val expected = result(snapshot(OWNER, ITEM))
                val delegate = Delegate(loadResults = ArrayDeque(listOf(expected, expected)))
                val events = mutableListOf<Todo18InventoryCacheSettlement>()
                val repository = Todo18InventoryCacheSettlementRepository(delegate, events::add)

                repository.acquire(request())
                assertSame(expected, repository.load())
                assertSame(expected, repository.load())

                assertEquals(listOf(settlement()), events)
            }
    }

    @Test
    fun `stale failed forbidden and unrelated snapshots do not disarm the exact acquisition`() =
        runTest {
            val qualifying = InventoryLoadResult.Partial(snapshot(OWNER, ITEM), stale = false)
            val delegate =
                Delegate(
                    loadResults =
                        ArrayDeque(
                            listOf(
                                ready(snapshot(OWNER, ITEM), stale = true),
                                InventoryLoadResult.Failed,
                                InventoryLoadResult.Forbidden,
                                ready(snapshot(OTHER_OWNER, ITEM), stale = false),
                                ready(snapshot(OWNER, OTHER_ITEM), stale = false),
                                qualifying,
                            )
                        )
                )
            val events = mutableListOf<Todo18InventoryCacheSettlement>()
            val repository = Todo18InventoryCacheSettlementRepository(delegate, events::add)

            repository.acquire(request())
            repeat(5) {
                repository.load()
                assertEquals(emptyList<Todo18InventoryCacheSettlement>(), events)
            }
            assertSame(qualifying, repository.load())

            assertEquals(listOf(settlement()), events)
        }

    @Test
    fun `non-success acquisition outcomes never arm settlement`() = runTest {
        listOf<InventoryAcquireResult>(
                InventoryAcquireResult.AlreadyOwned(receipt()),
                InventoryAcquireResult.Forbidden,
                InventoryAcquireResult.Failure(
                    com.planterior.helper.feature.shop.InventoryFailure.NETWORK,
                    OPERATION,
                ),
            )
            .forEach { outcome ->
                val delegate =
                    Delegate(loadResults = ArrayDeque(listOf(ready(snapshot(OWNER, ITEM), false))))
                delegate.acquireResult = outcome
                val events = mutableListOf<Todo18InventoryCacheSettlement>()
                val repository = Todo18InventoryCacheSettlementRepository(delegate, events::add)

                assertSame(outcome, repository.acquire(request()))
                repository.load()

                assertEquals(emptyList<Todo18InventoryCacheSettlement>(), events)
            }
    }

    @Test
    fun `delegate exception and cancellation identities remain primary`() = runTest {
        val loadFailure = IllegalStateException("load failed")
        val loadDelegate = Delegate(loadAction = { throw loadFailure })
        val loadRepository = Todo18InventoryCacheSettlementRepository(loadDelegate, onSettled = {})
        val actualLoadFailure =
            try {
                loadRepository.load()
                fail("Expected load failure")
            } catch (failure: IllegalStateException) {
                failure
            }
        assertSame(loadFailure, actualLoadFailure)

        val acquireCancellation = CancellationException("acquire cancelled")
        val acquireDelegate = Delegate(acquireAction = { throw acquireCancellation })
        val acquireRepository =
            Todo18InventoryCacheSettlementRepository(acquireDelegate, onSettled = {})
        val actualCancellation =
            try {
                acquireRepository.acquire(request())
                fail("Expected acquisition cancellation")
            } catch (failure: CancellationException) {
                failure
            }
        assertSame(acquireCancellation, actualCancellation)
    }

    @Test
    fun `observer RuntimeException and AssertionError cannot alter result or one-shot state`() =
        runTest {
            listOf<Throwable>(RuntimeException("observer"), AssertionError("observer")).forEach {
                observerFailure ->
                val expected = ready(snapshot(OWNER, ITEM), false)
                val delegate = Delegate(loadResults = ArrayDeque(listOf(expected, expected)))
                val repository =
                    Todo18InventoryCacheSettlementRepository(
                        delegate,
                        onSettled = { throw observerFailure },
                    )

                repository.acquire(request())
                assertSame(expected, repository.load())
                assertSame(expected, repository.load())
            }
        }

    @Test
    fun `diagnostic localizes exact successful settlement stages and load facts`() = runTest {
        val expected = ready(snapshot(OWNER, ITEM), stale = false)
        val delegate = Delegate(loadResults = ArrayDeque(listOf(expected)))
        val diagnostics = Todo18InventorySettlementDiagnosticRecorder()
        val repository =
            Todo18InventoryCacheSettlementRepository(
                delegate,
                onSettled = {},
                diagnostics = diagnostics,
            )

        repository.acquire(request())
        repository.load()

        val observations = diagnostics.snapshot()
        assertEquals(
            listOf(
                Todo18InventorySettlementStage.ACQUIRE_RESULT,
                Todo18InventorySettlementStage.ACQUISITION_ARMED,
                Todo18InventorySettlementStage.LOAD_ENTERED,
                Todo18InventorySettlementStage.LOAD_RETURNED,
                Todo18InventorySettlementStage.SETTLEMENT_ELIGIBILITY,
                Todo18InventorySettlementStage.SETTLEMENT_ATTEMPT,
                Todo18InventorySettlementStage.SETTLEMENT_EMISSION,
            ),
            observations.map { it.stage },
        )
        assertTrue(observations.all { it.settlement == settlement() })
        val returned = observations.single {
            it.stage == Todo18InventorySettlementStage.LOAD_RETURNED
        }
        assertEquals("Ready", returned.loadKind)
        assertEquals(false, returned.stale)
        assertEquals(listOf(ITEM), returned.ownedItemIds)
        assertEquals(
            true,
            observations
                .single {
                    it.stage == Todo18InventorySettlementStage.SETTLEMENT_ELIGIBILITY
                }
                .eligible,
        )
    }

    @Test
    fun `actual receipt reducer requires authoritative load eligibility and rendered semantics`() =
        runTest {
            val expected = ready(snapshot(OWNER, ITEM), stale = false)
            val diagnostics = Todo18InventorySettlementDiagnosticRecorder()
            val repository =
                Todo18InventoryCacheSettlementRepository(
                    Delegate(loadResults = ArrayDeque(listOf(expected))),
                    onSettled = {},
                    diagnostics = diagnostics,
                )
            repository.acquire(request())
            repository.load()
            val settlement = settlement()
            val complete =
                diagnostics.snapshot() +
                    Todo18InventorySettlementObservation(
                        Todo18InventorySettlementStage.BOUNDARY_DELIVERY,
                        settlement,
                    ) +
                    Todo18InventorySettlementObservation(
                        Todo18InventorySettlementStage.RENDERED_FEEDBACK,
                        settlement,
                        stale = false,
                        ownedItemIds = listOf(ITEM),
                        feedback = com.planterior.helper.feature.shop.InventoryFeedback.ACQUIRED,
                    )

            assertEquals(
                emptyList<String>(),
                Todo18InventorySettlementReceiptReducer.problems(complete),
            )
            listOf(
                    complete.map {
                        if (it.stage == Todo18InventorySettlementStage.LOAD_RETURNED) {
                            it.copy(stale = true)
                        } else it
                    },
                    complete.map {
                        if (it.stage == Todo18InventorySettlementStage.LOAD_RETURNED) {
                            it.copy(ownedItemIds = emptyList())
                        } else it
                    },
                    complete.map {
                        if (it.stage == Todo18InventorySettlementStage.SETTLEMENT_ELIGIBILITY) {
                            it.copy(eligible = false)
                        } else it
                    },
                    complete.map {
                        if (it.stage == Todo18InventorySettlementStage.RENDERED_FEEDBACK) {
                            it.copy(feedback = null)
                        } else it
                    },
                )
                .forEach { malformed ->
                    assertTrue(
                        Todo18InventorySettlementReceiptReducer.problems(malformed).isNotEmpty()
                    )
                }
        }

    private class Delegate(
        private val loadResults: ArrayDeque<InventoryLoadResult> = ArrayDeque(),
        private val loadAction: (suspend () -> InventoryLoadResult)? = null,
        private val acquireAction: (suspend () -> InventoryAcquireResult)? = null,
    ) : InventoryRepository {
        var acquireResult: InventoryAcquireResult = success()
        val loadForceRefreshes = mutableListOf<Boolean>()

        override suspend fun load(): InventoryLoadResult =
            loadAction?.invoke() ?: loadResults.removeFirst()

        override suspend fun load(forceRefresh: Boolean): InventoryLoadResult {
            loadForceRefreshes += forceRefresh
            return loadAction?.invoke() ?: loadResults.removeFirst()
        }

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult =
            acquireAction?.invoke() ?: acquireResult

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult = InventoryReceiptPresentationResult.Mismatch
    }

    private companion object {
        val OWNER = AccountId("todo18-owner")
        val OTHER_OWNER = AccountId("todo18-other-owner")
        val ITEM = ItemId("todo18-planter")
        val OTHER_ITEM = ItemId("todo18-other-item")
        val OPERATION = OperationId("todo18-inventory-operation")
        val NOW = Instant.parse("2026-08-26T03:00:00Z")
        val MEDIA =
            CatalogMediaIdentity(
                path = "catalog-assets/todo18-planter/${"a".repeat(64)}.webp",
                sha256 = "a".repeat(64),
                byteSize = 1,
                mimeType = "image/webp",
                width = 1,
                height = 1,
                mediaRevision = Revision(1),
            )

        fun request() = InventoryAcquireRequest(OWNER, ITEM, Revision(1), OPERATION)

        fun receipt() = InventoryOwnershipReceipt(OWNER, ITEM, Revision(1), Revision(1), NOW, MEDIA)

        fun success() = InventoryAcquireResult.Success(receipt())

        fun settlement() = Todo18InventoryCacheSettlement(OWNER, ITEM, OPERATION)

        fun ready(snapshot: InventorySnapshot, stale: Boolean) =
            InventoryLoadResult.Ready(snapshot, stale)

        fun snapshot(accountId: AccountId, ownedItemId: ItemId): InventorySnapshot =
            InventorySnapshot(
                accountId = accountId,
                catalog = listOf(item(ownedItemId)),
                owned =
                    listOf(
                        OwnedInventoryItem(
                            itemId = ownedItemId,
                            acquiredAt = NOW,
                            applied = false,
                            revision = Revision(1),
                        )
                    ),
                registeredPlantCount = 1,
                loadedAt = NOW,
            )

        fun item(itemId: ItemId) =
            InventoryItem(
                id = itemId,
                name = itemId.value,
                description = "Todo18 settlement fixture",
                category = ItemCategory.DECORATION,
                mediaIdentity = MEDIA,
                acquisitionCondition = null,
                revision = Revision(1),
                updatedAt = NOW,
            )
    }
}
