package com.planterior.helper.feature.shop

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InventoryDurableReceiptFifoTest {
    @Test
    fun `second acquisition completion cannot strand first claimed receipt behind newer action sequence`() =
        runTest {
            val clock = MutableClock(1_000)
            val scheduler = FakeScheduler(clock)
            val repository = FifoRepository(clock, OWNER, items(2))
            val operations =
                ArrayDeque(
                    listOf(
                        OperationId("operation-fifo-first"),
                        OperationId("operation-fifo-second"),
                    )
                )
            val controller = controller(repository, clock, scheduler, operations)
            repository.visibleState = { controller.state.value }
            controller.start(InventoryAuthOwnership.Authenticated(OWNER))
            val firstId = InventoryReceiptId("${OWNER.value}/operation-fifo-first")
            val secondId = InventoryReceiptId("${OWNER.value}/operation-fifo-second")
            val releaseFirstPresentation = repository.blockPresentation(firstId)

            val first = async { controller.acquire(ItemId("fifo-item-1")) }
            repository.presentationStarted(firstId).await()
            val second = async { controller.acquire(ItemId("fifo-item-2")) }
            repository.acquireStarted(ItemId("fifo-item-2")).await()
            runCurrent()
            releaseFirstPresentation.complete(Unit)
            first.await()
            second.await()

            var content = content(controller)
            assertEquals(firstId, content.feedbackReceiptId)
            assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, repository.phase(firstId))
            assertNull(repository.phase(secondId))
            assertTrue(repository.presentedWithoutMatchingVisibleToken.isEmpty())

            controller.feedbackConsumed(requireNotNull(content.feedbackPresentationToken))
            content = content(controller)
            assertEquals(secondId, content.feedbackReceiptId)
            assertEquals(listOf(firstId), repository.acknowledged)
        }

    @Test
    fun `three terminal operations stay FIFO while generic failure cannot replace visible receipt`() =
        runTest {
            val clock = MutableClock(10_000)
            val scheduler = FakeScheduler(clock)
            val repository = FifoRepository(clock, OWNER, items(4))
            repository.failureItems += ItemId("fifo-item-4")
            val operations = ArrayDeque((1..4).map { OperationId("operation-fifo-$it") })
            val controller = controller(repository, clock, scheduler, operations)
            repository.visibleState = { controller.state.value }
            controller.start(InventoryAuthOwnership.Authenticated(OWNER))

            controller.acquire(ItemId("fifo-item-1"))
            controller.acquire(ItemId("fifo-item-2"))
            controller.acquire(ItemId("fifo-item-3"))
            controller.acquire(ItemId("fifo-item-4"))

            val expected = (1..3).map { InventoryReceiptId("${OWNER.value}/operation-fifo-$it") }
            assertEquals(expected.first(), content(controller).feedbackReceiptId)
            assertEquals(InventoryFeedback.ACQUIRED, content(controller).feedback)
            expected.forEach { receiptId ->
                val visible = content(controller)
                assertEquals(receiptId, visible.feedbackReceiptId)
                controller.feedbackConsumed(requireNotNull(visible.feedbackPresentationToken))
            }

            assertEquals(expected, repository.acknowledged)
            assertNull(content(controller).feedbackReceiptId)
            assertTrue(repository.presentedWithoutMatchingVisibleToken.isEmpty())
        }

    @Test
    fun `presentation failure rolls back provisional UI and keeps claimed receipt retryable`() =
        runTest {
            val clock = MutableClock(20_000)
            val scheduler = FakeScheduler(clock)
            val repository = FifoRepository(clock, OWNER, items(1))
            val receiptId = InventoryReceiptId("${OWNER.value}/operation-presentation-failure")
            repository.failPresentation += receiptId
            val controller =
                controller(
                    repository,
                    clock,
                    scheduler,
                    ArrayDeque(listOf(OperationId("operation-presentation-failure"))),
                )
            repository.visibleState = { controller.state.value }
            controller.start(InventoryAuthOwnership.Authenticated(OWNER))

            controller.acquire(ItemId("fifo-item-1"))

            assertNull(content(controller).feedbackReceiptId)
            assertEquals(InventoryReceiptDeliveryPhase.CLAIMED, repository.phase(receiptId))
            assertEquals(1, scheduler.activeCount)
            assertTrue(repository.presentedWithoutMatchingVisibleToken.isEmpty())

            repository.failPresentation.clear()
            controller.retry()

            assertEquals(receiptId, content(controller).feedbackReceiptId)
            assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, repository.phase(receiptId))
            assertEquals(0, scheduler.activeCount)
        }

    @Test
    fun `recreation account switch and retry preserve oldest durable receipt without foreign publish`() =
        runTest {
            val clock = MutableClock(30_000)
            val repository = FifoRepository(clock, OWNER, items(1))
            val receipt = repository.seed("restart", createdAt = 1)
            val savedState = SavedStateHandle()
            val firstScheduler = FakeScheduler(clock)
            val first = InventoryController(repository, savedState, clock, firstScheduler)
            repository.visibleState = { first.state.value }
            first.start(InventoryAuthOwnership.Authenticated(OWNER))
            assertEquals(receipt.receiptId, content(first).feedbackReceiptId)

            val recreatedScheduler = FakeScheduler(clock)
            val recreated = InventoryController(repository, savedState, clock, recreatedScheduler)
            repository.visibleState = { recreated.state.value }
            recreated.start(InventoryAuthOwnership.Authenticated(OWNER))
            assertEquals(receipt.receiptId, content(recreated).feedbackReceiptId)

            repository.switchOwner(OTHER_OWNER)
            recreated.start(InventoryAuthOwnership.Authenticated(OTHER_OWNER))
            assertEquals(OTHER_OWNER, content(recreated).owner)
            assertNull(content(recreated).feedbackReceiptId)
            assertEquals(0, recreatedScheduler.activeCount)

            repository.switchOwner(OWNER)
            recreated.start(InventoryAuthOwnership.Authenticated(OWNER))
            assertEquals(receipt.receiptId, content(recreated).feedbackReceiptId)
            assertEquals(
                InventoryReceiptDeliveryPhase.PRESENTED,
                repository.phase(receipt.receiptId),
            )
            assertTrue(repository.presentedWithoutMatchingVisibleToken.isEmpty())
        }

    private fun controller(
        repository: InventoryRepository,
        clock: Clock,
        scheduler: InventoryReceiptRedeliveryScheduler,
        operations: ArrayDeque<OperationId>,
    ) =
        InventoryController(
            repository = repository,
            savedStateHandle = SavedStateHandle(),
            clock = clock,
            redeliveryScheduler = scheduler,
            operationIdFactory = { operations.removeFirst() },
        )

    private fun content(controller: InventoryController) =
        controller.state.value as InventoryUiState.Content

    private class FifoRepository(
        private val clock: Clock,
        initialOwner: AccountId,
        private val catalog: List<InventoryItem>,
    ) : InventoryRepository {
        private data class Row(
            val receipt: InventoryAcquisitionTerminalReceipt,
            var claim: InventoryReceiptClaim? = null,
        )

        private var owner = initialOwner
        private val rows = linkedMapOf<InventoryReceiptId, Row>()
        private val ownedItems = linkedSetOf<ItemId>()
        private val acquireSignals = mutableMapOf<ItemId, CompletableDeferred<Unit>>()
        private val presentationSignals =
            mutableMapOf<InventoryReceiptId, CompletableDeferred<Unit>>()
        private val presentationGates =
            mutableMapOf<InventoryReceiptId, CompletableDeferred<Unit>>()
        val failureItems = mutableSetOf<ItemId>()
        val failPresentation = mutableSetOf<InventoryReceiptId>()
        val acknowledged = mutableListOf<InventoryReceiptId>()
        val presentedWithoutMatchingVisibleToken = mutableListOf<InventoryReceiptId>()
        var visibleState: () -> InventoryUiState = { InventoryUiState.Loading(owner) }

        override suspend fun load(): InventoryLoadResult =
            InventoryLoadResult.Ready(
                snapshot(owner, catalog)
                    .copy(
                        owned =
                            ownedItems.map { itemId ->
                                OwnedInventoryItem(
                                    itemId,
                                    Instant.EPOCH,
                                    applied = false,
                                    revision = Revision(1),
                                )
                            }
                    ),
                false,
                rows.values
                    .filter { it.receipt.owner == owner }
                    .sortedWith(
                        compareBy<Row> { it.receipt.createdAtEpochMillis }
                            .thenBy { it.receipt.receiptId.value }
                    )
                    .map { it.receipt.receiptId },
            )

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
            acquireSignals.getOrPut(request.itemId, ::CompletableDeferred).complete(Unit)
            if (request.itemId in failureItems) {
                return InventoryAcquireResult.Failure(InventoryFailure.NETWORK, request.operationId)
            }
            val index = request.itemId.value.substringAfterLast('-').toLong()
            val ownership =
                InventoryOwnershipReceipt(
                    request.accountId,
                    request.itemId,
                    request.expectedCatalogRevision,
                    Revision(1),
                    Instant.ofEpochMilli(index),
                )
            val terminal =
                InventoryAcquisitionTerminalReceipt(
                    request.accountId,
                    request.itemId,
                    request.operationId,
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownership,
                    index,
                )
            rows.putIfAbsent(terminal.receiptId, Row(terminal))
            ownedItems += request.itemId
            return InventoryAcquireResult.Success(ownership)
        }

        fun acquireStarted(itemId: ItemId) = acquireSignals.getOrPut(itemId, ::CompletableDeferred)

        fun presentationStarted(receiptId: InventoryReceiptId) =
            presentationSignals.getOrPut(receiptId, ::CompletableDeferred)

        fun blockPresentation(receiptId: InventoryReceiptId): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { presentationGates[receiptId] = it }

        fun seed(id: String, createdAt: Long): InventoryAcquisitionTerminalReceipt {
            val item = catalog.first()
            val ownership =
                InventoryOwnershipReceipt(
                    owner,
                    item.id,
                    item.revision,
                    Revision(1),
                    Instant.ofEpochMilli(createdAt),
                )
            return InventoryAcquisitionTerminalReceipt(
                    owner,
                    item.id,
                    OperationId("operation-$id"),
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownership,
                    createdAt,
                )
                .also {
                    rows[it.receiptId] = Row(it)
                    ownedItems += it.itemId
                }
        }

        fun switchOwner(accountId: AccountId) {
            owner = accountId
        }

        fun phase(receiptId: InventoryReceiptId) = rows[receiptId]?.claim?.deliveryPhase

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            val row = rows[receiptId] ?: return InventoryReceiptClaimResult.Missing
            if (row.receipt.owner != owner) return InventoryReceiptClaimResult.Forbidden
            val current = row.claim
            if (current != null && current.claimant == claimant) {
                return InventoryReceiptClaimResult.Claimed(current)
            }
            if (
                current != null &&
                    current.claimant.presentationToken == claimant.presentationToken &&
                    (current.claimant.controllerEpoch < claimant.controllerEpoch ||
                        current.claimant.generation < claimant.generation)
            ) {
                val rebound = current.copy(claimant = claimant, rowVersion = current.rowVersion + 1)
                row.claim = rebound
                return InventoryReceiptClaimResult.Claimed(rebound)
            }
            if (current != null && current.leaseExpiresAtEpochMillis > clock.millis()) {
                return InventoryReceiptClaimResult.Unavailable(current.leaseExpiresAtEpochMillis)
            }
            val claim =
                InventoryReceiptClaim(
                    row.receipt,
                    claimant,
                    (current?.rowVersion ?: 0) + 1,
                    clock.millis() + 5_000,
                    current?.deliveryPhase ?: InventoryReceiptDeliveryPhase.CLAIMED,
                )
            row.claim = claim
            return InventoryReceiptClaimResult.Claimed(claim)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            val row =
                rows[claim.receipt.receiptId] ?: return InventoryReceiptPresentationResult.Mismatch
            if (row.claim != claim) return InventoryReceiptPresentationResult.Mismatch
            presentationSignals
                .getOrPut(claim.receipt.receiptId, ::CompletableDeferred)
                .complete(Unit)
            presentationGates[claim.receipt.receiptId]?.await()
            if (claim.receipt.receiptId in failPresentation) {
                return InventoryReceiptPresentationResult.DatabaseFailure
            }
            val visibleToken =
                (visibleState() as? InventoryUiState.Content)?.feedbackPresentationToken
            if (visibleToken != claim.feedbackPresentationToken()) {
                presentedWithoutMatchingVisibleToken += claim.receipt.receiptId
            }
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            row.claim = presented
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            val row =
                rows[claim.receipt.receiptId]
                    ?: return InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED
            if (row.claim != claim) return InventoryReceiptAcknowledgement.MISMATCH
            rows.remove(claim.receipt.receiptId)
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class MutableClock(private var value: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(value)

        override fun millis(): Long = value
    }

    private class FakeScheduler(private val clock: Clock) : InventoryReceiptRedeliveryScheduler {
        private data class Scheduled(var cancelled: Boolean = false)

        private val scheduled = mutableListOf<Scheduled>()
        val activeCount: Int
            get() = scheduled.count { !it.cancelled }

        override fun schedule(
            delayMillis: Long,
            task: suspend () -> Unit,
        ): InventoryReceiptRedeliveryHandle {
            require(delayMillis >= 0)
            require(clock.millis() + delayMillis >= clock.millis())
            val entry = Scheduled()
            scheduled += entry
            return InventoryReceiptRedeliveryHandle { entry.cancelled = true }
        }
    }

    private companion object {
        val OWNER = AccountId("fifo-owner")
        val OTHER_OWNER = AccountId("fifo-other-owner")

        fun items(count: Int) =
            (1..count).map { index ->
                InventoryItem(
                    ItemId("fifo-item-$index"),
                    "fifo item $index",
                    "fifo item $index description",
                    ItemCategory.DECORATION,
                    "catalog-assets/fifo-item-$index/preview.webp",
                    null,
                    Revision(1),
                    Instant.EPOCH,
                )
            }

        fun snapshot(owner: AccountId, catalog: List<InventoryItem>) =
            InventorySnapshot(owner, catalog, emptyList(), 1, Instant.EPOCH)
    }
}
