package com.planterior.helper.feature.shop

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryAcknowledgementRecoveryTest {
    @Test
    fun `N transient acknowledgement failures recover through exact scheduled backoff`() = runTest {
        val repository = RecoveryRepository(listOf(receipt("scheduled"))).apply { ackFailures = 3 }
        val scheduler = FakeRetryScheduler()
        val backoff = RecordingBackoff()
        val controller = controller(repository, SavedStateHandle(), scheduler, backoff)
        controller.start(auth(OWNER))
        val token = requireNotNull(content(controller).feedbackPresentationToken)

        controller.feedbackConsumed(token)
        assertEquals(token, content(controller).feedbackPresentationToken)
        assertEquals(listOf(10L), scheduler.delays)

        scheduler.runNext()
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(listOf(1, 2, 3), backoff.attempts)
        assertEquals(listOf(10L, 20L, 30L), scheduler.delays)
        assertEquals(4, repository.ackAttempts)
        assertEquals(listOf(token.receiptId), repository.acknowledged)
        assertNull(content(controller).feedbackPresentationToken)
    }

    @Test
    fun `explicit retry directly retries pending acknowledgement without second UI consumption`() =
        runTest {
            val repository =
                RecoveryRepository(listOf(receipt("explicit"))).apply { ackFailures = 1 }
            val scheduler = FakeRetryScheduler()
            val controller = controller(repository, SavedStateHandle(), scheduler)
            controller.start(auth(OWNER))
            val token = requireNotNull(content(controller).feedbackPresentationToken)

            controller.feedbackConsumed(token)
            controller.retry()

            assertEquals(2, repository.ackAttempts)
            assertEquals(listOf(token.receiptId), repository.acknowledged)
            assertEquals(0, scheduler.activeCount)
            assertNull(content(controller).feedbackPresentationToken)
        }

    @Test
    fun `process death before durable consumed marker never auto acknowledges`() = runTest {
        val receipt = receipt("before-marker")
        val repository = RecoveryRepository(listOf(receipt)).apply { consumptionFailures = 1 }
        val savedState = SavedStateHandle()
        val firstScheduler = FakeRetryScheduler()
        val first = controller(repository, savedState, firstScheduler)
        first.start(auth(OWNER))
        first.feedbackConsumed(requireNotNull(content(first).feedbackPresentationToken))
        first.clear()

        val recreated = controller(repository, savedState, FakeRetryScheduler())
        recreated.start(auth(OWNER))

        assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, repository.phase(receipt.receiptId))
        assertEquals(receipt.receiptId, content(recreated).feedbackReceiptId)
        assertEquals(0, repository.ackAttempts)
        assertTrue(repository.acknowledged.isEmpty())
    }

    @Test
    fun `process death after durable consumed marker resumes ack without duplicate presentation`() =
        runTest {
            val receipt = receipt("after-marker")
            val repository = RecoveryRepository(listOf(receipt)).apply { ackFailures = 1 }
            val savedState = SavedStateHandle()
            val first = controller(repository, savedState, FakeRetryScheduler())
            first.start(auth(OWNER))
            first.feedbackConsumed(requireNotNull(content(first).feedbackPresentationToken))
            assertEquals(
                InventoryReceiptDeliveryPhase.ACK_PENDING,
                repository.phase(receipt.receiptId),
            )
            assertEquals(1, repository.presentationWrites)
            first.clear()

            val recreated = controller(repository, savedState, FakeRetryScheduler())
            recreated.start(auth(OWNER))

            assertEquals(listOf(receipt.receiptId), repository.acknowledged)
            assertEquals(1, repository.presentationWrites)
            assertNull(content(recreated).feedbackPresentationToken)
        }

    @Test
    fun `database unavailable while recording consumption stays retryable and visible`() = runTest {
        val receipt = receipt("database")
        val repository = RecoveryRepository(listOf(receipt)).apply { consumptionFailures = 2 }
        val scheduler = FakeRetryScheduler()
        val controller = controller(repository, SavedStateHandle(), scheduler)
        controller.start(auth(OWNER))
        val token = requireNotNull(content(controller).feedbackPresentationToken)

        controller.feedbackConsumed(token)
        scheduler.runNext()
        assertEquals(token, content(controller).feedbackPresentationToken)
        assertEquals(0, repository.ackAttempts)

        controller.retry()
        assertEquals(listOf(receipt.receiptId), repository.acknowledged)
        assertNull(content(controller).feedbackPresentationToken)
    }

    @Test
    fun `stale consumed token reloads authoritative Room candidates instead of looping`() =
        runTest {
            val receipt = receipt("stale")
            val repository = RecoveryRepository(listOf(receipt))
            val controller = controller(repository, SavedStateHandle(), FakeRetryScheduler())
            controller.start(auth(OWNER))
            val token = requireNotNull(content(controller).feedbackPresentationToken)
            val loadsBefore = repository.loads
            repository.consumeTerminal = InventoryReceiptConsumptionResult.Stale

            controller.feedbackConsumed(token)

            assertTrue(repository.loads > loadsBefore)
            assertNull(content(controller).feedbackPresentationToken)
            assertEquals(0, repository.ackAttempts)
        }

    @Test
    fun `owner switch cancels old owner acknowledgement retry`() = runTest {
        val receipt = receipt("owner-switch")
        val repository = RecoveryRepository(listOf(receipt)).apply { ackFailures = 1 }
        val scheduler = FakeRetryScheduler()
        val controller = controller(repository, SavedStateHandle(), scheduler)
        controller.start(auth(OWNER))
        controller.feedbackConsumed(requireNotNull(content(controller).feedbackPresentationToken))

        repository.owner = OTHER_OWNER
        controller.start(auth(OTHER_OWNER))
        scheduler.runAll()

        assertEquals(OTHER_OWNER, content(controller).owner)
        assertEquals(1, repository.ackAttempts)
        assertTrue(repository.acknowledged.isEmpty())
        assertEquals(0, scheduler.activeCount)
    }

    @Test
    fun `older pending ack blocks FIFO until success then presents exactly next receipt`() =
        runTest {
            val first = receipt("fifo-first", createdAt = 1)
            val second = receipt("fifo-second", createdAt = 2)
            val repository = RecoveryRepository(listOf(first, second)).apply { ackFailures = 1 }
            val scheduler = FakeRetryScheduler()
            val controller = controller(repository, SavedStateHandle(), scheduler)
            controller.start(auth(OWNER))
            assertEquals(first.receiptId, content(controller).feedbackReceiptId)

            controller.feedbackConsumed(
                requireNotNull(content(controller).feedbackPresentationToken)
            )
            assertEquals(first.receiptId, content(controller).feedbackReceiptId)
            scheduler.runNext()
            assertEquals(second.receiptId, content(controller).feedbackReceiptId)
            controller.feedbackConsumed(
                requireNotNull(content(controller).feedbackPresentationToken)
            )

            assertEquals(listOf(first.receiptId, second.receiptId), repository.acknowledged)
            assertNull(content(controller).feedbackReceiptId)
        }

    @Test
    fun `controller clear cancels retry while durable ack pending resumes in next controller`() =
        runTest {
            val receipt = receipt("clear")
            val repository = RecoveryRepository(listOf(receipt)).apply { ackFailures = 1 }
            val savedState = SavedStateHandle()
            val scheduler = FakeRetryScheduler()
            val first = controller(repository, savedState, scheduler)
            first.start(auth(OWNER))
            first.feedbackConsumed(requireNotNull(content(first).feedbackPresentationToken))

            first.clear()
            scheduler.runAll()
            assertEquals(1, repository.ackAttempts)
            assertEquals(0, scheduler.activeCount)

            val restarted = controller(repository, savedState, FakeRetryScheduler())
            restarted.start(auth(OWNER))
            assertEquals(listOf(receipt.receiptId), repository.acknowledged)
            assertNull(content(restarted).feedbackPresentationToken)
        }

    @Test
    fun `unconsumed presented row remains visible across recreation and is never auto acked`() =
        runTest {
            val receipt = receipt("unconsumed")
            val repository = RecoveryRepository(listOf(receipt))
            val savedState = SavedStateHandle()
            val first = controller(repository, savedState, FakeRetryScheduler())
            first.start(auth(OWNER))
            first.clear()

            val restarted = controller(repository, savedState, FakeRetryScheduler())
            restarted.start(auth(OWNER))

            assertEquals(receipt.receiptId, content(restarted).feedbackReceiptId)
            assertEquals(0, repository.consumptionAttempts)
            assertEquals(0, repository.ackAttempts)
        }

    private fun controller(
        repository: InventoryRepository,
        savedState: SavedStateHandle,
        scheduler: FakeRetryScheduler,
        backoff: InventoryAcknowledgementRetryBackoff = RecordingBackoff(),
    ) =
        InventoryController(
            repository = repository,
            savedStateHandle = savedState,
            acknowledgementRetryScheduler = scheduler,
            acknowledgementRetryBackoff = backoff,
        )

    private fun content(controller: InventoryController) =
        controller.state.value as InventoryUiState.Content

    private class RecordingBackoff : InventoryAcknowledgementRetryBackoff {
        val attempts = mutableListOf<Int>()

        override fun delayMillis(attempt: Int): Long {
            attempts += attempt
            return attempt * 10L
        }
    }

    private class FakeRetryScheduler : InventoryReceiptRedeliveryScheduler {
        private data class Task(
            val delay: Long,
            val block: suspend () -> Unit,
            var cancelled: Boolean = false,
            var completed: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()
        val delays = mutableListOf<Long>()
        val activeCount: Int
            get() = tasks.count { !it.cancelled && !it.completed }

        override fun schedule(
            delayMillis: Long,
            task: suspend () -> Unit,
        ): InventoryReceiptRedeliveryHandle {
            require(delayMillis >= 0)
            delays += delayMillis
            val entry = Task(delayMillis, task)
            tasks += entry
            return InventoryReceiptRedeliveryHandle { entry.cancelled = true }
        }

        suspend fun runNext() {
            val next = tasks.firstOrNull { !it.cancelled && !it.completed } ?: return
            next.completed = true
            next.block()
        }

        suspend fun runAll() {
            while (activeCount > 0) runNext()
        }
    }

    private class RecoveryRepository(receipts: List<InventoryAcquisitionTerminalReceipt>) :
        InventoryRepository {
        private data class Row(
            val receipt: InventoryAcquisitionTerminalReceipt,
            var claim: InventoryReceiptClaim? = null,
        )

        var owner: AccountId = OWNER
        var ackFailures = 0
        var consumptionFailures = 0
        var consumeTerminal: InventoryReceiptConsumptionResult? = null
        var loads = 0
        var consumptionAttempts = 0
        var ackAttempts = 0
        var presentationWrites = 0
        val acknowledged = mutableListOf<InventoryReceiptId>()
        private val rows = receipts.associate { it.receiptId to Row(it) }.toMutableMap()

        fun phase(receiptId: InventoryReceiptId) = rows[receiptId]?.claim?.deliveryPhase

        override suspend fun load(): InventoryLoadResult {
            loads += 1
            return InventoryLoadResult.Ready(
                snapshot(owner),
                false,
                rows.values
                    .filter { it.receipt.owner == owner }
                    .sortedWith(
                        compareBy<Row> { it.receipt.createdAtEpochMillis }
                            .thenBy { it.receipt.receiptId.value }
                    )
                    .map { it.receipt.receiptId },
            )
        }

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult =
            error("not used")

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
            val claim =
                InventoryReceiptClaim(
                    row.receipt,
                    claimant,
                    (current?.rowVersion ?: 0) + 1,
                    10_000,
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
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            row.claim = presented
            presentationWrites += 1
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun markReceiptConsumed(
            claim: InventoryReceiptClaim
        ): InventoryReceiptConsumptionResult {
            consumptionAttempts += 1
            if (consumptionFailures > 0) {
                consumptionFailures -= 1
                return InventoryReceiptConsumptionResult.DatabaseFailure
            }
            consumeTerminal?.let { terminal ->
                rows.remove(claim.receipt.receiptId)
                consumeTerminal = null
                return terminal
            }
            val row =
                rows[claim.receipt.receiptId] ?: return InventoryReceiptConsumptionResult.Missing
            if (row.claim != claim) return InventoryReceiptConsumptionResult.Stale
            val pending = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.ACK_PENDING)
            row.claim = pending
            return InventoryReceiptConsumptionResult.PendingAcknowledgement(pending)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            ackAttempts += 1
            if (ackFailures > 0) {
                ackFailures -= 1
                return InventoryReceiptAcknowledgement.DATABASE_FAILURE
            }
            val row =
                rows[claim.receipt.receiptId]
                    ?: return InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED
            if (
                row.claim != claim ||
                    claim.deliveryPhase != InventoryReceiptDeliveryPhase.ACK_PENDING
            ) {
                return InventoryReceiptAcknowledgement.MISMATCH
            }
            rows.remove(claim.receipt.receiptId)
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private companion object {
        val OWNER = AccountId("ack-owner")
        val OTHER_OWNER = AccountId("ack-other-owner")

        fun auth(owner: AccountId) = InventoryAuthOwnership.Authenticated(owner)

        fun receipt(id: String, createdAt: Long = 1): InventoryAcquisitionTerminalReceipt {
            val itemId = ItemId("item-$id")
            return InventoryAcquisitionTerminalReceipt(
                OWNER,
                itemId,
                OperationId("operation-$id"),
                InventoryOwnershipReceiptKind.ACQUIRED,
                InventoryOwnershipReceipt(
                    OWNER,
                    itemId,
                    Revision(1),
                    Revision(1),
                    Instant.ofEpochMilli(createdAt),
                ),
                createdAt,
            )
        }

        fun snapshot(owner: AccountId) =
            InventorySnapshot(
                owner,
                listOf(
                    InventoryItem(
                        ItemId("catalog-${owner.value}"),
                        "catalog ${owner.value}",
                        "catalog ${owner.value} description",
                        ItemCategory.DECORATION,
                        "catalog-assets/${owner.value}/preview.webp",
                        null,
                        Revision(1),
                        Instant.EPOCH,
                    )
                ),
                emptyList(),
                0,
                Instant.EPOCH,
            )
    }
}
