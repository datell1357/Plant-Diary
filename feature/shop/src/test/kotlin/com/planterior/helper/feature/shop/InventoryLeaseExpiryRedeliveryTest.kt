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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryLeaseExpiryRedeliveryTest {
    @Test
    fun `foreign claimed receipt stays on screen then presents exactly at lease expiry`() =
        runTest {
            val clock = MutableClock(1_000)
            val scheduler = FakeRedeliveryScheduler(clock)
            val receipt = receipt("first", createdAt = 1)
            val repository = LeaseRepository(clock, listOf(receipt))
            repository.foreignClaim(receipt.receiptId, retryAt = 2_000)
            val controller = controller(repository, clock, scheduler)

            controller.start(InventoryAuthOwnership.Authenticated(OWNER))

            assertTrue(controller.state.value is InventoryUiState.Content)
            assertNull(content(controller).feedbackPresentationToken)
            assertEquals(listOf(1_000L), scheduler.scheduledDelays)
            scheduler.advanceTo(1_999)
            assertNull(content(controller).feedbackPresentationToken)

            scheduler.advanceTo(2_000)

            assertEquals(receipt.receiptId, content(controller).feedbackReceiptId)
            assertEquals(
                InventoryReceiptDeliveryPhase.PRESENTED,
                repository.phase(receipt.receiptId),
            )
            assertEquals(0, repository.strandedClaimCount(clock.millis()))
        }

    @Test
    fun `process restart before and after expiry schedules remaining time and resumes durable row`() =
        runTest {
            val clock = MutableClock(10_000)
            val receipt = receipt("restart", createdAt = 1)
            val repository = LeaseRepository(clock, listOf(receipt))
            repository.foreignClaim(
                receipt.receiptId,
                retryAt = 12_000,
                phase = InventoryReceiptDeliveryPhase.PRESENTED,
            )
            val beforeScheduler = FakeRedeliveryScheduler(clock)
            val before = controller(repository, clock, beforeScheduler)
            before.start(InventoryAuthOwnership.Authenticated(OWNER))
            assertEquals(listOf(2_000L), beforeScheduler.scheduledDelays)
            before.clear()

            clock.set(11_000)
            val recreatedScheduler = FakeRedeliveryScheduler(clock)
            val recreated = controller(repository, clock, recreatedScheduler)
            recreated.start(InventoryAuthOwnership.Authenticated(OWNER))
            assertEquals(listOf(1_000L), recreatedScheduler.scheduledDelays)
            recreated.clear()

            clock.set(12_001)
            val afterExpiry = controller(repository, clock, FakeRedeliveryScheduler(clock))
            afterExpiry.start(InventoryAuthOwnership.Authenticated(OWNER))

            assertEquals(receipt.receiptId, content(afterExpiry).feedbackReceiptId)
            assertEquals(
                InventoryReceiptDeliveryPhase.PRESENTED,
                repository.phase(receipt.receiptId),
            )
            assertEquals(0, repository.strandedClaimCount(clock.millis()))
        }

    @Test
    fun `owner switch cancels foreign owner deadline and cannot publish it`() = runTest {
        val clock = MutableClock(20_000)
        val scheduler = FakeRedeliveryScheduler(clock)
        val receipt = receipt("owner-switch", createdAt = 1)
        val repository = LeaseRepository(clock, listOf(receipt))
        repository.foreignClaim(receipt.receiptId, retryAt = 21_000)
        val controller = controller(repository, clock, scheduler)
        controller.start(InventoryAuthOwnership.Authenticated(OWNER))

        repository.switchOwner(OTHER_OWNER)
        controller.start(InventoryAuthOwnership.Authenticated(OTHER_OWNER))
        assertEquals(0, scheduler.activeCount)
        scheduler.advanceTo(21_000)

        assertEquals(OTHER_OWNER, content(controller).owner)
        assertNull(content(controller).feedbackReceiptId)
        assertEquals(1, repository.claimAttempts.count { it == receipt.receiptId })
    }

    @Test
    fun `claim and acknowledgement before deadline cancel the old timer`() = runTest {
        val clock = MutableClock(30_000)
        val scheduler = FakeRedeliveryScheduler(clock)
        val receipt = receipt("ack-before-timer", createdAt = 1)
        val repository = LeaseRepository(clock, listOf(receipt))
        repository.foreignClaim(receipt.receiptId, retryAt = 35_000)
        val controller = controller(repository, clock, scheduler)
        controller.start(InventoryAuthOwnership.Authenticated(OWNER))

        repository.releaseClaim(receipt.receiptId)
        controller.retry()
        val token = requireNotNull(content(controller).feedbackPresentationToken)
        controller.feedbackConsumed(token)

        assertEquals(0, scheduler.activeCount)
        scheduler.advanceTo(35_000)
        assertNull(content(controller).feedbackReceiptId)
        assertEquals(listOf(receipt.receiptId), repository.acknowledged)
    }

    @Test
    fun `renewed lease replaces one timer with the exact later deadline`() = runTest {
        val clock = MutableClock(40_000)
        val scheduler = FakeRedeliveryScheduler(clock)
        val receipt = receipt("renewed", createdAt = 1)
        val repository = LeaseRepository(clock, listOf(receipt))
        repository.foreignClaim(receipt.receiptId, retryAt = 41_000)
        val controller = controller(repository, clock, scheduler)
        controller.start(InventoryAuthOwnership.Authenticated(OWNER))

        repository.renewForeignClaim(receipt.receiptId, retryAt = 43_000)
        scheduler.advanceTo(41_000)

        assertNull(content(controller).feedbackReceiptId)
        assertEquals(listOf(1_000L, 2_000L), scheduler.scheduledDelays)
        assertEquals(1, scheduler.activeCount)
        scheduler.advanceTo(43_000)
        assertEquals(receipt.receiptId, content(controller).feedbackReceiptId)
        assertEquals(0, repository.strandedClaimCount(clock.millis()))
    }

    @Test
    fun `earlier leased receipt blocks later receipt and one timer wakes the ordered queue`() =
        runTest {
            val clock = MutableClock(50_000)
            val scheduler = FakeRedeliveryScheduler(clock)
            val first = receipt("ordered-first", createdAt = 1)
            val second = receipt("ordered-second", createdAt = 2)
            val repository = LeaseRepository(clock, listOf(first, second))
            repository.foreignClaim(first.receiptId, retryAt = 51_000)
            val controller = controller(repository, clock, scheduler)
            controller.start(InventoryAuthOwnership.Authenticated(OWNER))

            assertEquals(listOf(first.receiptId), repository.claimAttempts)
            assertEquals(1, scheduler.activeCount)
            scheduler.advanceTo(51_000)
            assertEquals(first.receiptId, content(controller).feedbackReceiptId)
            controller.feedbackConsumed(
                requireNotNull(content(controller).feedbackPresentationToken)
            )
            assertEquals(second.receiptId, content(controller).feedbackReceiptId)
            controller.feedbackConsumed(
                requireNotNull(content(controller).feedbackPresentationToken)
            )

            assertEquals(listOf(first.receiptId, second.receiptId), repository.acknowledged)
            assertEquals(0, scheduler.activeCount)
            assertEquals(0, repository.strandedClaimCount(clock.millis()))
        }

    @Test
    fun `retries before one deadline do not create duplicate timers`() = runTest {
        val clock = MutableClock(60_000)
        val scheduler = FakeRedeliveryScheduler(clock)
        val receipt = receipt("duplicate-timer", createdAt = 1)
        val repository = LeaseRepository(clock, listOf(receipt))
        repository.foreignClaim(receipt.receiptId, retryAt = 61_000)
        val controller = controller(repository, clock, scheduler)
        controller.start(InventoryAuthOwnership.Authenticated(OWNER))

        controller.retry()
        controller.retry()

        assertEquals(1, scheduler.scheduleCount)
        assertEquals(1, scheduler.activeCount)
        scheduler.advanceTo(61_000)
        assertEquals(receipt.receiptId, content(controller).feedbackReceiptId)
    }

    @Test
    fun `controller clear cancels lease redelivery and leaves durable row for next process`() =
        runTest {
            val clock = MutableClock(70_000)
            val scheduler = FakeRedeliveryScheduler(clock)
            val receipt = receipt("clear", createdAt = 1)
            val repository = LeaseRepository(clock, listOf(receipt))
            repository.foreignClaim(receipt.receiptId, retryAt = 71_000)
            val controller = controller(repository, clock, scheduler)
            controller.start(InventoryAuthOwnership.Authenticated(OWNER))

            controller.clear()
            assertEquals(0, scheduler.activeCount)
            scheduler.advanceTo(71_000)

            assertNull(content(controller).feedbackReceiptId)
            assertEquals(InventoryReceiptDeliveryPhase.CLAIMED, repository.phase(receipt.receiptId))
            val restarted = controller(repository, clock, FakeRedeliveryScheduler(clock))
            restarted.start(InventoryAuthOwnership.Authenticated(OWNER))
            assertEquals(receipt.receiptId, content(restarted).feedbackReceiptId)
            assertEquals(0, repository.strandedClaimCount(clock.millis()))
        }

    private fun controller(
        repository: InventoryRepository,
        clock: Clock,
        scheduler: InventoryReceiptRedeliveryScheduler,
    ) =
        InventoryController(
            repository = repository,
            savedStateHandle = SavedStateHandle(),
            clock = clock,
            redeliveryScheduler = scheduler,
        )

    private fun content(controller: InventoryController) =
        controller.state.value as InventoryUiState.Content

    private class MutableClock(private var epochMillis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(epochMillis)

        override fun millis(): Long = epochMillis

        fun set(value: Long) {
            require(value >= epochMillis)
            epochMillis = value
        }
    }

    private class FakeRedeliveryScheduler(private val clock: MutableClock) :
        InventoryReceiptRedeliveryScheduler {
        private data class Scheduled(
            val dueAt: Long,
            val task: suspend () -> Unit,
            var cancelled: Boolean = false,
            var completed: Boolean = false,
        )

        private val tasks = mutableListOf<Scheduled>()
        val scheduledDelays = mutableListOf<Long>()
        val scheduleCount: Int
            get() = tasks.size

        val activeCount: Int
            get() = tasks.count { !it.cancelled && !it.completed }

        override fun schedule(
            delayMillis: Long,
            task: suspend () -> Unit,
        ): InventoryReceiptRedeliveryHandle {
            require(delayMillis >= 0)
            scheduledDelays += delayMillis
            val scheduled = Scheduled(clock.millis() + delayMillis, task)
            tasks += scheduled
            return InventoryReceiptRedeliveryHandle { scheduled.cancelled = true }
        }

        suspend fun advanceTo(epochMillis: Long) {
            clock.set(epochMillis)
            while (true) {
                val next =
                    tasks
                        .filter { !it.cancelled && !it.completed && it.dueAt <= clock.millis() }
                        .minByOrNull { it.dueAt } ?: return
                next.completed = true
                next.task()
            }
        }
    }

    private class LeaseRepository(
        private val clock: Clock,
        receipts: List<InventoryAcquisitionTerminalReceipt>,
    ) : InventoryRepository {
        private data class Row(
            val receipt: InventoryAcquisitionTerminalReceipt,
            var claim: InventoryReceiptClaim? = null,
        )

        private var activeOwner = OWNER
        private val rows =
            receipts
                .sortedBy { it.createdAtEpochMillis }
                .associate { it.receiptId to Row(it) }
                .toMutableMap()
        val claimAttempts = mutableListOf<InventoryReceiptId>()
        val acknowledged = mutableListOf<InventoryReceiptId>()

        fun foreignClaim(
            receiptId: InventoryReceiptId,
            retryAt: Long,
            phase: InventoryReceiptDeliveryPhase = InventoryReceiptDeliveryPhase.CLAIMED,
        ) {
            rows.getValue(receiptId).claim =
                InventoryReceiptClaim(
                    rows.getValue(receiptId).receipt,
                    InventoryReceiptClaimant("foreign-process", 1, 1),
                    1,
                    retryAt,
                    phase,
                )
        }

        fun renewForeignClaim(receiptId: InventoryReceiptId, retryAt: Long) {
            val row = rows.getValue(receiptId)
            row.claim = requireNotNull(row.claim).copy(leaseExpiresAtEpochMillis = retryAt)
        }

        fun releaseClaim(receiptId: InventoryReceiptId) {
            rows.getValue(receiptId).claim = null
        }

        fun switchOwner(owner: AccountId) {
            activeOwner = owner
        }

        fun phase(receiptId: InventoryReceiptId) = rows[receiptId]?.claim?.deliveryPhase

        fun strandedClaimCount(now: Long) =
            rows.values.count {
                val claim = it.claim
                claim?.deliveryPhase == InventoryReceiptDeliveryPhase.CLAIMED &&
                    claim.leaseExpiresAtEpochMillis <= now
            }

        override suspend fun load(): InventoryLoadResult =
            InventoryLoadResult.Ready(
                snapshot(activeOwner),
                false,
                rows.values
                    .filter { it.receipt.owner == activeOwner }
                    .sortedBy { it.receipt.createdAtEpochMillis }
                    .map { it.receipt.receiptId },
            )

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult =
            error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            claimAttempts += receiptId
            val row = rows[receiptId] ?: return InventoryReceiptClaimResult.Missing
            if (row.receipt.owner != activeOwner) return InventoryReceiptClaimResult.Forbidden
            val current = row.claim
            if (current != null && current.claimant == claimant) {
                return InventoryReceiptClaimResult.Claimed(current)
            }
            if (current != null && current.leaseExpiresAtEpochMillis > clock.millis()) {
                return InventoryReceiptClaimResult.Unavailable(current.leaseExpiresAtEpochMillis)
            }
            val replacement =
                InventoryReceiptClaim(
                    row.receipt,
                    claimant,
                    (current?.rowVersion ?: 0) + 1,
                    clock.millis() + 5_000,
                    current?.deliveryPhase ?: InventoryReceiptDeliveryPhase.CLAIMED,
                )
            row.claim = replacement
            return InventoryReceiptClaimResult.Claimed(replacement)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            val row =
                rows[claim.receipt.receiptId] ?: return InventoryReceiptPresentationResult.Mismatch
            if (row.claim != claim) return InventoryReceiptPresentationResult.Mismatch
            val presented =
                if (claim.deliveryPhase == InventoryReceiptDeliveryPhase.PRESENTED) claim
                else claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
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

    private companion object {
        val OWNER = AccountId("lease-owner")
        val OTHER_OWNER = AccountId("other-owner")

        fun receipt(id: String, createdAt: Long): InventoryAcquisitionTerminalReceipt {
            val itemId = ItemId("item-$id")
            val ownership =
                InventoryOwnershipReceipt(
                    OWNER,
                    itemId,
                    Revision(1),
                    Revision(1),
                    Instant.ofEpochMilli(createdAt),
                )
            return InventoryAcquisitionTerminalReceipt(
                OWNER,
                itemId,
                OperationId("operation-$id"),
                InventoryOwnershipReceiptKind.ACQUIRED,
                ownership,
                createdAt,
            )
        }

        fun snapshot(owner: AccountId): InventorySnapshot =
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
