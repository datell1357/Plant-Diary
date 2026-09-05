package com.planterior.helper.feature.shop

import androidx.lifecycle.SavedStateHandle
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InventoryControllerTest {
    @Test
    fun `explicit retry forces remote refresh even after fresh content`() = runTest {
        val repository = ControlledRepository()
        val account = AccountId("account-a")
        repeat(2) {
            repository.loads +=
                ControlledCall(
                    completed(InventoryLoadResult.Ready(snapshot(account, "same"), false))
                )
        }
        val controller = InventoryController(repository, SavedStateHandle())
        controller.start(InventoryAuthOwnership.Authenticated(account))
        assertEquals(0, repository.forceRefreshCalls)
        controller.retry()
        assertEquals(1, repository.forceRefreshCalls)
    }

    @Test
    fun `stale cached content publishes first then forced refresh publishes current content`() =
        runTest {
            val repository = ControlledRepository()
            val account = AccountId("account-a")
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, "cached"),
                            true,
                            refreshRequired = true,
                        )
                    )
                )
            val refresh = ControlledCall<InventoryLoadResult>()
            repository.loads += refresh
            val controller = InventoryController(repository, SavedStateHandle())
            val start = async { controller.start(InventoryAuthOwnership.Authenticated(account)) }
            refresh.started.await()
            val content = controller.state.value as InventoryUiState.Content
            assertEquals("cached", content.snapshot.catalog.single().id.value)
            assertTrue(content.stale)
            assertTrue(repository.forceRefreshCalls > 0)
            refresh.result.complete(
                InventoryLoadResult.Ready(snapshot(account, "fresh").copy(generation = 2), false)
            )
            start.await()
            assertEquals(
                "fresh",
                (controller.state.value as InventoryUiState.Content)
                    .snapshot
                    .catalog
                    .single()
                    .id
                    .value,
            )
        }

    @Test
    fun `A post-acquire forbidden cannot invalidate B load after exact owner switch race`() =
        runTest {
            val accountA = AccountId("account-a")
            val accountB = AccountId("account-b")
            val itemA = item("item-a")
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(InventoryLoadResult.Ready(snapshot(accountA, itemA.id.value), false))
                )
            val acquireA = ControlledCall<InventoryAcquireResult>()
            repository.acquisitions += acquireA
            val staleReloadA = ControlledCall<InventoryLoadResult>(nonCancellable = true)
            val loadB = ControlledCall<InventoryLoadResult>()
            repository.loads += staleReloadA
            repository.loads += loadB
            val saved = SavedStateHandle()
            val controller =
                InventoryController(repository, saved) { OperationId("operation-fixed-0001") }

            controller.start(InventoryAuthOwnership.Authenticated(accountA))
            val acquisition = async { controller.acquire(itemA.id) }
            acquireA.started.await()
            acquireA.result.complete(success(accountA, itemA))
            staleReloadA.started.await()

            val switchToB = async {
                controller.start(InventoryAuthOwnership.Authenticated(accountB))
            }
            runCurrent()
            assertFalse(loadB.started.isCompleted)
            staleReloadA.result.complete(InventoryLoadResult.Forbidden)
            loadB.started.await()
            loadB.result.complete(InventoryLoadResult.Ready(snapshot(accountB, "item-b"), false))
            acquisition.join()
            switchToB.await()

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(accountB, content.owner)
            assertEquals(ItemId("item-b"), content.snapshot.catalog.single().id)
            assertNull(saved.get<String>("inventory.pending.operation"))
        }

    @Test
    fun `account switch neutralizes A before B load and late A completion cannot publish`() =
        runTest {
            val repository = DeferredRepository()
            val controller =
                InventoryController(repository, SavedStateHandle()) {
                    OperationId("operation-fixed-0001")
                }
            val accountA = AccountId("account-a")
            val accountB = AccountId("account-b")
            val loadAResult = CompletableDeferred<InventoryLoadResult>()
            val loadBResult = CompletableDeferred<InventoryLoadResult>()
            repository.loads.add(loadAResult)
            repository.loads.add(loadBResult)

            val loadA = async { controller.start(InventoryAuthOwnership.Authenticated(accountA)) }
            runCurrent()
            assertEquals(InventoryUiState.Loading(accountA), controller.state.value)
            val loadB = async { controller.start(InventoryAuthOwnership.Authenticated(accountB)) }
            runCurrent()
            loadA.join()
            assertTrue(loadA.isCancelled)
            assertEquals(InventoryUiState.Loading(accountB), controller.state.value)
            loadBResult.complete(InventoryLoadResult.Ready(snapshot(accountB, "b"), true))
            loadB.await()

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(accountB, content.owner)
            assertEquals(ItemId("b"), content.snapshot.catalog.single().id)
            assertEquals(true, content.stale)
        }

    @Test
    fun `A to B to A cancels and joins both obsolete generations before final A publish`() =
        runTest {
            val accountA = AccountId("account-a")
            val accountB = AccountId("account-b")
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(InventoryLoadResult.Ready(snapshot(accountA, "initial-a"), false))
                )
            val staleA = ControlledCall<InventoryLoadResult>(nonCancellable = true)
            val finalA = ControlledCall<InventoryLoadResult>()
            repository.loads += staleA
            repository.loads += finalA
            val controller = InventoryController(repository, SavedStateHandle())
            controller.start(InventoryAuthOwnership.Authenticated(accountA))

            val retryA = async { controller.retry() }
            staleA.started.await()
            val switchB = async { controller.start(InventoryAuthOwnership.Authenticated(accountB)) }
            runCurrent()
            val switchBackA = async {
                controller.start(InventoryAuthOwnership.Authenticated(accountA))
            }
            runCurrent()
            staleA.result.complete(
                InventoryLoadResult.Ready(snapshot(accountA, "obsolete-a"), false)
            )
            finalA.started.await()
            finalA.result.complete(InventoryLoadResult.Ready(snapshot(accountA, "final-a"), false))
            retryA.join()
            switchB.join()
            switchBackA.await()

            assertTrue(retryA.isCancelled)
            assertTrue(switchB.isCancelled)
            val content = controller.state.value as InventoryUiState.Content
            assertEquals(accountA, content.owner)
            assertEquals(ItemId("final-a"), content.snapshot.catalog.single().id)
        }

    @Test
    fun `logout joins non-cancellable acquisition and rejects stale filter and saved-state writes`() =
        runTest {
            val accountA = AccountId("account-a")
            val itemA = item("item-a")
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(InventoryLoadResult.Ready(snapshot(accountA, itemA.id.value), false))
                )
            val acquireA = ControlledCall<InventoryAcquireResult>(nonCancellable = true)
            repository.acquisitions += acquireA
            val saved = SavedStateHandle()
            val controller =
                InventoryController(repository, saved) { OperationId("operation-fixed-0001") }
            controller.start(InventoryAuthOwnership.Authenticated(accountA))
            controller.selectSection(InventorySection.SHOP)

            val acquisition = async { controller.acquire(itemA.id) }
            acquireA.started.await()
            val logout = async { controller.start(InventoryAuthOwnership.SignedOut) }
            runCurrent()
            controller.selectCategory(ItemCategory.BACKGROUND)
            controller.search("stale A filter")
            acquireA.result.complete(success(accountA, itemA))
            acquisition.join()
            logout.await()

            assertTrue(acquisition.isCancelled)
            assertEquals(InventoryUiState.Forbidden, controller.state.value)
            assertNull(saved.get<String>("inventory.category"))
            assertNull(saved.get<String>("inventory.search"))
            assertNull(saved.get<String>("inventory.pending.owner"))
            assertNull(saved.get<String>("inventory.pending.operation"))
            assertEquals(InventorySection.SHOP.name, saved.get<String>("inventory.section"))
        }

    @Test
    fun `stale A success is suppressed and current B load failure remains correlated to B`() =
        runTest {
            val accountA = AccountId("account-a")
            val accountB = AccountId("account-b")
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(InventoryLoadResult.Ready(snapshot(accountA, "initial-a"), false))
                )
            val staleA = ControlledCall<InventoryLoadResult>(nonCancellable = true)
            val failedB = ControlledCall<InventoryLoadResult>()
            repository.loads += staleA
            repository.loads += failedB
            val controller = InventoryController(repository, SavedStateHandle())
            controller.start(InventoryAuthOwnership.Authenticated(accountA))

            val retryA = async { controller.retry() }
            staleA.started.await()
            val switchB = async { controller.start(InventoryAuthOwnership.Authenticated(accountB)) }
            runCurrent()
            staleA.result.complete(
                InventoryLoadResult.Ready(snapshot(accountA, "stale-success-a"), false)
            )
            failedB.started.await()
            failedB.result.complete(InventoryLoadResult.Failed)
            retryA.join()
            switchB.await()

            assertTrue(retryA.isCancelled)
            assertEquals(InventoryUiState.Error(accountB), controller.state.value)
        }

    @Test
    fun `current B forbidden load remains B error and cannot change authoritative owner`() =
        runTest {
            val accountB = AccountId("account-b")
            val repository = ControlledRepository()
            repository.loads += ControlledCall(completed(InventoryLoadResult.Forbidden))
            val controller = InventoryController(repository, SavedStateHandle())

            controller.start(InventoryAuthOwnership.Authenticated(accountB))

            assertEquals(InventoryUiState.Error(accountB), controller.state.value)
        }

    @Test
    fun `same-owner reload keeps its action correlation and is not cancelled`() = runTest {
        val account = AccountId("account-a")
        val repository = ControlledRepository()
        repository.loads +=
            ControlledCall(
                completed(InventoryLoadResult.Ready(snapshot(account, "initial"), false))
            )
        val sameOwnerReload = ControlledCall<InventoryLoadResult>(nonCancellable = true)
        repository.loads += sameOwnerReload
        val controller = InventoryController(repository, SavedStateHandle())
        controller.start(InventoryAuthOwnership.Authenticated(account))

        val retry = async { controller.retry() }
        sameOwnerReload.started.await()
        sameOwnerReload.result.complete(
            InventoryLoadResult.Ready(snapshot(account, "same-owner").copy(generation = 2), true)
        )
        retry.await()

        assertEquals(false, retry.isCancelled)
        val content = controller.state.value as InventoryUiState.Content
        assertEquals(account, content.owner)
        assertEquals(ItemId("same-owner"), content.snapshot.catalog.single().id)
        assertEquals(true, content.stale)
    }

    @Test
    fun `verified source epoch supersedes unverified ordering once then hash ordering is strict`() =
        runTest {
            val account = AccountId("account-a")
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, "legacy").copy(generation = 9, verified = false),
                            true,
                        )
                    )
                )
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, "authoritative").copy(generation = 1),
                            false,
                        )
                    )
                )
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, "late-unverified")
                                .copy(generation = 10, verified = false),
                            true,
                        )
                    )
                )
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, "same-generation-conflict").copy(generation = 1),
                            false,
                        )
                    )
                )
            val controller = InventoryController(repository, SavedStateHandle())

            controller.start(InventoryAuthOwnership.Authenticated(account))
            assertFalse((controller.state.value as InventoryUiState.Content).snapshot.verified)
            controller.retry()
            var content = controller.state.value as InventoryUiState.Content
            assertTrue(content.snapshot.verified)
            assertEquals(ItemId("authoritative"), content.snapshot.catalog.single().id)

            controller.retry()
            content = controller.state.value as InventoryUiState.Content
            assertTrue(content.snapshot.verified)
            assertEquals(ItemId("authoritative"), content.snapshot.catalog.single().id)

            controller.retry()
            content = controller.state.value as InventoryUiState.Content
            assertTrue(content.snapshot.verified)
            assertEquals(ItemId("authoritative"), content.snapshot.catalog.single().id)
        }

    @Test
    fun `held original and replay converge to persisted already owned receipt feedback`() =
        runTest {
            val account = AccountId("account-a")
            val acquiredItem = item("same-owner-race-item")
            val operationId = OperationId("operation-same-owner-race")
            val receipt = ownershipReceipt(account, acquiredItem)
            val terminal =
                terminalReceipt(
                    operationId,
                    InventoryOwnershipReceiptKind.ALREADY_OWNED,
                    receipt,
                )
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, acquiredItem.id.value),
                            false,
                        )
                    )
                )
            val original = ControlledCall<InventoryAcquireResult>(nonCancellable = true)
            repository.acquisitions += original
            val replayLoad = ControlledCall<InventoryLoadResult>()
            val originalReload = ControlledCall<InventoryLoadResult>()
            repository.loads += replayLoad
            repository.loads += originalReload
            val saved = SavedStateHandle()
            val controller = InventoryController(repository, saved) { operationId }
            controller.start(InventoryAuthOwnership.Authenticated(account))

            val acquisition = async { controller.acquire(acquiredItem.id) }
            original.started.await()
            val replay = async { controller.retry() }
            replayLoad.started.await()
            replayLoad.result.complete(
                InventoryLoadResult.Ready(
                    ownedSnapshot(account, acquiredItem),
                    false,
                    listOf(terminal.receiptId),
                )
            )
            replay.await()
            val replayFeedback = (controller.state.value as InventoryUiState.Content).feedback

            original.result.complete(InventoryAcquireResult.AlreadyOwned(receipt))
            originalReload.started.await()
            originalReload.result.complete(
                InventoryLoadResult.Ready(ownedSnapshot(account, acquiredItem), false)
            )
            acquisition.await()

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(acquiredItem.id, content.snapshot.owned.single().itemId)
            assertEquals(InventoryFeedback.ALREADY_OWNED, replayFeedback)
            assertEquals(InventoryFeedback.ALREADY_OWNED, content.feedback)
            assertNull(content.acquiringItemId)
            assertNull(saved.get<String>("inventory.pending.operation"))
        }

    @Test
    fun `restored pending acquired receipt publishes acquired while owned load without receipt is silent`() =
        runTest {
            val account = AccountId("account-a")
            val acquiredItem = item("restored-item")
            val operationId = OperationId("operation-restored-acquired")
            val pending =
                mapOf(
                    "inventory.pending.owner" to account.value,
                    "inventory.pending.operation" to operationId.value,
                    "inventory.pending.item" to acquiredItem.id.value,
                    "inventory.pending.revision" to acquiredItem.revision.value,
                )
            val receipt = ownershipReceipt(account, acquiredItem)
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            ownedSnapshot(account, acquiredItem),
                            false,
                            listOf(
                                terminalReceipt(
                                        operationId,
                                        InventoryOwnershipReceiptKind.ACQUIRED,
                                        receipt,
                                    )
                                    .receiptId
                            ),
                        )
                    )
                )
            val restored = InventoryController(repository, SavedStateHandle(pending))

            restored.start(InventoryAuthOwnership.Authenticated(account))

            assertEquals(
                InventoryFeedback.ACQUIRED,
                (restored.state.value as InventoryUiState.Content).feedback,
            )

            val plainRepository = QueueRepository(ownedSnapshot(account, acquiredItem))
            val plain = InventoryController(plainRepository, SavedStateHandle())
            plain.start(InventoryAuthOwnership.Authenticated(account))
            assertNull((plain.state.value as InventoryUiState.Content).feedback)
        }

    @Test
    fun `response loss restart publishes persisted already owned receipt rather than inferred acquired`() =
        runTest {
            val account = AccountId("account-a")
            val acquiredItem = item("restart-already-owned")
            val operationId = OperationId("operation-restart-already-owned")
            val receipt = ownershipReceipt(account, acquiredItem)
            val saved =
                SavedStateHandle(
                    mapOf(
                        "inventory.pending.owner" to account.value,
                        "inventory.pending.operation" to operationId.value,
                        "inventory.pending.item" to acquiredItem.id.value,
                        "inventory.pending.revision" to acquiredItem.revision.value,
                    )
                )
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            ownedSnapshot(account, acquiredItem),
                            false,
                            listOf(
                                terminalReceipt(
                                        operationId,
                                        InventoryOwnershipReceiptKind.ALREADY_OWNED,
                                        receipt,
                                    )
                                    .receiptId
                            ),
                        )
                    )
                )

            val controller = InventoryController(repository, saved)
            controller.start(InventoryAuthOwnership.Authenticated(account))

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(InventoryFeedback.ALREADY_OWNED, content.feedback)
            assertNull(saved.get<String>("inventory.pending.operation"))
        }

    @Test
    fun `distinct newer operation feedback survives stale older receipt reload`() = runTest {
        val account = AccountId("account-a")
        val firstItem = item("first-item")
        val secondItem = item("second-item")
        val firstOperation = OperationId("operation-first-item")
        val secondOperation = OperationId("operation-second-item")
        val operationIds = ArrayDeque(listOf(firstOperation, secondOperation))
        val repository = ControlledRepository()
        repository.loads +=
            ControlledCall(
                completed(
                    InventoryLoadResult.Ready(
                        inventorySnapshot(account, listOf(firstItem, secondItem))
                            .copy(generation = 1),
                        false,
                    )
                )
            )
        val firstAcquire = ControlledCall<InventoryAcquireResult>()
        val secondAcquire = ControlledCall<InventoryAcquireResult>()
        repository.acquisitions += firstAcquire
        repository.acquisitions += secondAcquire
        val staleFirstReload = ControlledCall<InventoryLoadResult>(nonCancellable = true)
        val secondReload = ControlledCall<InventoryLoadResult>()
        repository.loads += staleFirstReload
        repository.loads += secondReload
        val controller =
            InventoryController(repository, SavedStateHandle()) { operationIds.removeFirst() }
        controller.start(InventoryAuthOwnership.Authenticated(account))

        val first = async { controller.acquire(firstItem.id) }
        firstAcquire.started.await()
        firstAcquire.result.complete(success(account, firstItem))
        staleFirstReload.started.await()

        val second = async { controller.acquire(secondItem.id) }
        secondAcquire.started.await()
        secondAcquire.result.complete(
            InventoryAcquireResult.AlreadyOwned(ownershipReceipt(account, secondItem))
        )
        secondReload.started.await()
        secondReload.result.complete(
            InventoryLoadResult.Ready(
                inventorySnapshot(
                        account,
                        listOf(firstItem, secondItem),
                        listOf(firstItem, secondItem),
                    )
                    .copy(generation = 3),
                false,
            )
        )
        second.await()
        assertEquals(
            InventoryFeedback.ALREADY_OWNED,
            (controller.state.value as InventoryUiState.Content).feedback,
        )

        staleFirstReload.result.complete(
            InventoryLoadResult.Ready(
                inventorySnapshot(account, listOf(firstItem, secondItem), listOf(firstItem))
                    .copy(generation = 2),
                false,
            )
        )
        first.await()

        val final = controller.state.value as InventoryUiState.Content
        assertEquals(InventoryFeedback.ALREADY_OWNED, final.feedback)
        assertEquals(
            "${account.value}/${secondOperation.value}",
            final.feedbackReceiptId?.value,
        )
        assertEquals(secondItem.id, repository.claimedExpected.single().receipt.itemId)
        assertEquals(secondOperation, repository.claimedExpected.single().operationId)
        assertEquals(
            listOf(firstItem.id, secondItem.id),
            final.snapshot.owned.map { it.itemId }.sortedBy { it.value },
        )
        assertEquals(3L, final.snapshot.generation)
    }

    @Test
    fun `account switch rejects stale foreign terminal receipt feedback`() = runTest {
        val accountA = AccountId("account-a")
        val accountB = AccountId("account-b")
        val itemA = item("item-a")
        val itemB = item("item-b")
        val staleReceipt = ownershipReceipt(accountA, itemA)
        val repository = ControlledRepository()
        repository.loads +=
            ControlledCall(
                completed(InventoryLoadResult.Ready(snapshot(accountA, "item-a"), false))
            )
        repository.loads +=
            ControlledCall(
                completed(
                    InventoryLoadResult.Ready(
                        snapshot(accountB, "item-b"),
                        false,
                        listOf(
                            terminalReceipt(
                                    OperationId("operation-stale-account-a"),
                                    InventoryOwnershipReceiptKind.ACQUIRED,
                                    staleReceipt,
                                )
                                .receiptId
                        ),
                    )
                )
            )
        val controller = InventoryController(repository, SavedStateHandle())
        controller.start(InventoryAuthOwnership.Authenticated(accountA))

        controller.start(InventoryAuthOwnership.Authenticated(accountB))

        val content = controller.state.value as InventoryUiState.Content
        assertEquals(accountB, content.owner)
        assertEquals(itemB.id, content.snapshot.catalog.single().id)
        assertNull(content.feedback)
    }

    @Test
    fun `process restore discards foreign pending saved state before B acquisition`() = runTest {
        val accountB = AccountId("account-b")
        val itemB = item("item-b")
        val saved =
            SavedStateHandle(
                mapOf(
                    "inventory.pending.owner" to "account-a",
                    "inventory.pending.operation" to "operation-account-a",
                    "inventory.pending.item" to itemB.id.value,
                    "inventory.pending.revision" to itemB.revision.value,
                )
            )
        val repository = QueueRepository(snapshot(accountB, itemB.id.value))
        repository.results += success(accountB, itemB)
        val controller =
            InventoryController(repository, saved) { OperationId("operation-account-b") }

        controller.start(InventoryAuthOwnership.Authenticated(accountB))
        controller.acquire(itemB.id)

        assertEquals(
            listOf("operation-account-b"),
            repository.operations.map { it.value },
        )
        val content = controller.state.value as InventoryUiState.Content
        assertEquals(accountB, content.owner)
        assertEquals(itemB.id, content.snapshot.owned.single().itemId)
        assertNull(saved.get<String>("inventory.pending.owner"))
        assertNull(saved.get<String>("inventory.pending.operation"))
    }

    @Test
    fun `response loss retry reuses one durable operation and publishes immediate acquired ownership`() =
        runTest {
            val account = AccountId("account-a")
            val item = item("decor-a")
            val repository = QueueRepository(snapshot(account, item.id.value))
            repository.results.add(InventoryAcquireResult.Failure(InventoryFailure.NETWORK))
            repository.results.add(success(account, item))
            val controller =
                InventoryController(repository, SavedStateHandle()) {
                    OperationId("operation-fixed-0001")
                }
            controller.start(InventoryAuthOwnership.Authenticated(account))
            controller.acquire(item.id)
            assertEquals(
                InventoryFeedback.NETWORK_FAILURE,
                (controller.state.value as InventoryUiState.Content).feedback,
            )
            controller.acquire(item.id)

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(InventoryFeedback.ACQUIRED, content.feedback)
            assertEquals(
                listOf("operation-fixed-0001", "operation-fixed-0001"),
                repository.operations.map { it.value },
            )
            assertEquals(item.id, content.snapshot.owned.single().itemId)
            assertNull(content.acquiringItemId)
        }

    @Test
    fun `delayed acquisition reload publishes feedback only with authoritative ownership`() =
        runTest {
            val account = AccountId("account-a")
            val acquiredItem = item("delayed-reload-acked-item")
            val operationId = OperationId("operation-delayed-reload-acked")
            val receipt =
                terminalReceipt(
                    operationId,
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, acquiredItem),
                )
            val repository =
                DelayedReloadLedgerRepository(
                    snapshot(account, acquiredItem.id.value),
                    ownedSnapshot(account, acquiredItem),
                    receipt,
                )
            val controller =
                InventoryController(
                    repository,
                    SavedStateHandle(),
                    operationIdFactory = { operationId },
                )
            controller.start(InventoryAuthOwnership.Authenticated(account))

            val acquisition = async { controller.acquire(acquiredItem.id) }
            repository.reloadStarted.await()
            var content = controller.state.value as InventoryUiState.Content
            assertNull(content.feedback)
            assertNull(content.feedbackReceiptId)

            repository.releaseReload.complete(Unit)
            acquisition.await()

            content = controller.state.value as InventoryUiState.Content
            assertEquals(receipt.receiptId, content.feedbackReceiptId)
            assertEquals(acquiredItem.id, content.snapshot.owned.single().itemId)

            controller.consumeVisibleFeedback()
            content = controller.state.value as InventoryUiState.Content
            assertNull(content.feedback)
            assertNull(content.feedbackReceiptId)
            assertEquals(1, repository.acknowledgements)
        }

    @Test
    fun `terminal candidate waits through failed forbidden stale and missing ownership reloads`() =
        runTest {
            val account = AccountId("account-a")
            val acquiredItem = item("retained-terminal-item")
            val operationId = OperationId("operation-retained-terminal")
            val receipt = ownershipReceipt(account, acquiredItem)
            val terminalResults =
                listOf(
                    InventoryAcquireResult.Success(receipt) to InventoryFeedback.ACQUIRED,
                    InventoryAcquireResult.AlreadyOwned(receipt) to InventoryFeedback.ALREADY_OWNED,
                )
            val blockedReloads =
                listOf(
                    InventoryLoadResult.Failed,
                    InventoryLoadResult.Forbidden,
                    InventoryLoadResult.Ready(ownedSnapshot(account, acquiredItem), stale = true),
                    InventoryLoadResult.Ready(snapshot(account, acquiredItem.id.value), false),
                )

            terminalResults.forEach { (terminalResult, expectedFeedback) ->
                blockedReloads.forEach { blockedReload ->
                    val repository = ControlledRepository()
                    repository.loads +=
                        ControlledCall(
                            completed(
                                InventoryLoadResult.Ready(
                                    snapshot(account, acquiredItem.id.value),
                                    false,
                                )
                            )
                        )
                    repository.acquisitions += ControlledCall(completed(terminalResult))
                    repository.loads += ControlledCall(completed(blockedReload))
                    repository.loads +=
                        ControlledCall(
                            completed(
                                InventoryLoadResult.Ready(
                                    ownedSnapshot(account, acquiredItem),
                                    false,
                                )
                            )
                        )
                    val controller =
                        InventoryController(repository, SavedStateHandle()) { operationId }
                    controller.start(InventoryAuthOwnership.Authenticated(account))

                    controller.acquire(acquiredItem.id)

                    var content = controller.state.value as InventoryUiState.Content
                    assertNull(content.feedback)
                    assertEquals(0, repository.claimAttempts)

                    controller.retry()

                    content = controller.state.value as InventoryUiState.Content
                    assertEquals(expectedFeedback, content.feedback)
                    assertEquals(acquiredItem.id, content.snapshot.owned.single().itemId)
                    assertEquals(
                        "${account.value}/${operationId.value}",
                        content.feedbackReceiptId?.value,
                    )
                    assertEquals(1, repository.claimAttempts)
                    assertEquals(
                        acquiredItem.id,
                        repository.claimedExpected.single().receipt.itemId,
                    )
                }
            }
        }

    @Test
    fun `cancelled authoritative reload retains exact terminal candidate for later load`() =
        runTest {
            val account = AccountId("account-a")
            val acquiredItem = item("cancelled-reload-item")
            val operationId = OperationId("operation-cancelled-reload")
            val repository = ControlledRepository()
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(
                            snapshot(account, acquiredItem.id.value),
                            false,
                        )
                    )
                )
            repository.acquisitions += ControlledCall(completed(success(account, acquiredItem)))
            val cancelledReload = ControlledCall<InventoryLoadResult>()
            repository.loads += cancelledReload
            repository.loads +=
                ControlledCall(
                    completed(
                        InventoryLoadResult.Ready(ownedSnapshot(account, acquiredItem), false)
                    )
                )
            val controller = InventoryController(repository, SavedStateHandle()) { operationId }
            controller.start(InventoryAuthOwnership.Authenticated(account))

            val acquisition = async { controller.acquire(acquiredItem.id) }
            val completion = CompletableDeferred<Throwable?>()
            acquisition.invokeOnCompletion(completion::complete)
            cancelledReload.started.await()
            val expected = CancellationException("cancel authoritative reload")
            acquisition.cancel(expected)
            acquisition.join()

            assertSame(expected, completion.await())
            assertEquals(0, repository.claimAttempts)

            controller.retry()

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(InventoryFeedback.ACQUIRED, content.feedback)
            assertEquals(acquiredItem.id, content.snapshot.owned.single().itemId)
            assertEquals("${account.value}/${operationId.value}", content.feedbackReceiptId?.value)
            assertEquals(1, repository.claimAttempts)
        }

    @Test
    fun `delayed acquisition payload cannot republish a receipt acknowledged before response`() =
        runTest {
            val account = AccountId("account-a")
            val item = item("delayed-acquire-item")
            val operationId = OperationId("operation-delayed-acquire")
            val receipt =
                terminalReceipt(
                    operationId,
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, item),
                )
            val repository =
                DelayedAcquireAcknowledgedRepository(
                    inventorySnapshot(account, listOf(item)),
                    receipt,
                )
            val controller = InventoryController(repository, SavedStateHandle()) { operationId }
            controller.start(InventoryAuthOwnership.Authenticated(account))

            val acquisition = async { controller.acquire(item.id) }
            repository.acquireStarted.await()
            repository.acknowledgeBeforeResponse()
            repository.releaseAcquire.complete(Unit)
            acquisition.await()

            val content = controller.state.value as InventoryUiState.Content
            assertNull(content.feedback)
            assertNull(content.feedbackReceiptId)
            assertEquals(0, repository.claimAttempts)
        }

    @Test
    fun `stale claim result preserves authoritative candidate for explicit redelivery`() = runTest {
        val account = AccountId("account-a")
        val ownedItem = item("stale-race-item")
        val receipt =
            terminalReceipt(
                OperationId("operation-stale-race"),
                InventoryOwnershipReceiptKind.ACQUIRED,
                ownershipReceipt(account, ownedItem),
            )
        val repository = StaleThenDeliveryRepository(ownedSnapshot(account, ownedItem), receipt)
        val controller = InventoryController(repository, SavedStateHandle())

        controller.start(InventoryAuthOwnership.Authenticated(account))
        assertNull((controller.state.value as InventoryUiState.Content).feedbackReceiptId)

        controller.retry()
        val content = controller.state.value as InventoryUiState.Content
        assertEquals(receipt.receiptId, content.feedbackReceiptId)
        assertEquals(2, repository.claimAttempts)
        controller.consumeVisibleFeedback()
        assertEquals(listOf(receipt.receiptId), repository.acknowledged)
    }

    @Test
    fun `N concurrent retries serialize one receipt claim and one acknowledgement`() = runTest {
        val account = AccountId("account-a")
        val ownedItem = item("serialized-n-retry-item")
        val receipt =
            terminalReceipt(
                OperationId("operation-serialized-n-retry"),
                InventoryOwnershipReceiptKind.ALREADY_OWNED,
                ownershipReceipt(account, ownedItem),
            )
        val repository = ConcurrentRetryRepository(ownedSnapshot(account, ownedItem), receipt)
        val controller = InventoryController(repository, SavedStateHandle())
        controller.start(InventoryAuthOwnership.Authenticated(account))

        val retries = List(16) { async { controller.retry() } }
        repository.claimStarted.await()
        runCurrent()
        try {
            assertEquals(1, repository.claimAttempts)
        } finally {
            repository.releaseClaim.complete(Unit)
        }
        retries.forEach { it.await() }

        val content = controller.state.value as InventoryUiState.Content
        assertEquals(receipt.receiptId, content.feedbackReceiptId)
        assertEquals(1, repository.claimAttempts)
        controller.consumeVisibleFeedback()
        assertEquals(listOf(receipt.receiptId), repository.acknowledged)
    }

    @Test
    fun `exactly two concurrent retries serialize one receipt claim and never strand claimed`() =
        runTest {
            val account = AccountId("account-a")
            val ownedItem = item("serialized-retry-item")
            val receipt =
                terminalReceipt(
                    OperationId("operation-serialized-retry"),
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, ownedItem),
                )
            val repository = ConcurrentRetryRepository(ownedSnapshot(account, ownedItem), receipt)
            val controller = InventoryController(repository, SavedStateHandle())
            controller.start(InventoryAuthOwnership.Authenticated(account))

            val first = async { controller.retry() }
            repository.claimStarted.await()
            val second = async { controller.retry() }
            runCurrent()
            try {
                assertEquals(1, repository.claimAttempts)
            } finally {
                repository.releaseClaim.complete(Unit)
            }
            first.await()
            second.await()

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(receipt.receiptId, content.feedbackReceiptId)
            assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, repository.deliveryPhase)
            controller.consumeVisibleFeedback()
            assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, repository.deliveryPhase)
            assertEquals(listOf(receipt.receiptId), repository.acknowledged)
        }

    @Test
    fun `cancelled claim winner leaves durable claim for the next retry to present`() = runTest {
        val account = AccountId("account-a")
        val ownedItem = item("cancelled-winner-item")
        val receipt =
            terminalReceipt(
                OperationId("operation-cancelled-winner"),
                InventoryOwnershipReceiptKind.ACQUIRED,
                ownershipReceipt(account, ownedItem),
            )
        val repository = CancelledWinnerRepository(ownedSnapshot(account, ownedItem), receipt)
        val controller = InventoryController(repository, SavedStateHandle())
        controller.start(InventoryAuthOwnership.Authenticated(account))

        val cancelledWinner = async { controller.retry() }
        repository.claimPersisted.await()
        cancelledWinner.cancelAndJoin()
        assertEquals(InventoryReceiptDeliveryPhase.CLAIMED, repository.deliveryPhase)
        assertNull((controller.state.value as InventoryUiState.Content).feedbackReceiptId)

        controller.retry()
        val content = controller.state.value as InventoryUiState.Content
        assertEquals(receipt.receiptId, content.feedbackReceiptId)
        assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, repository.deliveryPhase)
        controller.consumeVisibleFeedback()
        assertEquals(listOf(receipt.receiptId), repository.acknowledged)
    }

    @Test
    fun `process death mid-batch waits for lease expiry then redelivers in order once`() = runTest {
        val account = AccountId("account-a")
        val items = (1..3).map { item("death-batch-item-$it") }
        val receipts = items.mapIndexed { index, item ->
            terminalReceipt(
                    OperationId("operation-death-batch-${index + 1}"),
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, item),
                )
                .copy(createdAtEpochMillis = (index + 1).toLong())
        }
        val repository =
            BarrierBatchClaimRepository(
                inventorySnapshot(account, items, items),
                receipts,
            )
        val beforeDeath = InventoryController(repository, SavedStateHandle())
        beforeDeath.start(InventoryAuthOwnership.Authenticated(account))
        val interruptedBatch = async { beforeDeath.retry() }
        repository.secondClaimStarted.await()

        val afterDeath = InventoryController(repository, SavedStateHandle())
        afterDeath.start(InventoryAuthOwnership.Authenticated(account))
        assertNull((afterDeath.state.value as InventoryUiState.Content).feedbackReceiptId)

        repository.expireClaims()
        afterDeath.retry()
        val announced = mutableListOf<InventoryReceiptId>()
        receipts.forEach { expected ->
            val content = afterDeath.state.value as InventoryUiState.Content
            assertEquals(expected.receiptId, content.feedbackReceiptId)
            announced += requireNotNull(content.feedbackReceiptId)
            afterDeath.consumeVisibleFeedback()
        }
        assertEquals(receipts.map { it.receiptId }, announced)
        assertEquals(announced.distinct(), announced)

        repository.releaseSecondClaim.complete(Unit)
        interruptedBatch.await()
        assertNull((afterDeath.state.value as InventoryUiState.Content).feedbackReceiptId)
        assertEquals(receipts.map { it.receiptId }, repository.acknowledged)
    }

    @Test
    fun `owner switch during a blocked receipt batch cannot enqueue the prior owner claim`() =
        runTest {
            val accountA = AccountId("account-a")
            val accountB = AccountId("account-b")
            val items = (1..3).map { item("owner-batch-item-$it") }
            val receipts = items.mapIndexed { index, item ->
                terminalReceipt(
                        OperationId("operation-owner-batch-${index + 1}"),
                        InventoryOwnershipReceiptKind.ACQUIRED,
                        ownershipReceipt(accountA, item),
                    )
                    .copy(createdAtEpochMillis = (index + 1).toLong())
            }
            val repository =
                BarrierBatchClaimRepository(
                    inventorySnapshot(accountA, items, items),
                    receipts,
                )
            val controller = InventoryController(repository, SavedStateHandle())
            controller.start(InventoryAuthOwnership.Authenticated(accountA))

            val staleBatch = async { controller.retry() }
            repository.secondClaimStarted.await()
            repository.switchOwner(snapshot(accountB, "owner-b-item"))
            val switch = async {
                controller.start(InventoryAuthOwnership.Authenticated(accountB))
            }
            runCurrent()
            repository.releaseSecondClaim.complete(Unit)
            staleBatch.join()
            switch.await()

            val content = controller.state.value as InventoryUiState.Content
            assertEquals(accountB, content.owner)
            assertNull(content.feedback)
            assertNull(content.feedbackReceiptId)
            assertTrue(repository.acknowledged.isEmpty())
        }

    @Test
    fun `durable receipts publish and acknowledge one exact live feedback in deterministic order`() =
        runTest {
            val account = AccountId("account-a")
            val firstItem = item("already-owned-first")
            val secondItem = item("acquired-second")
            val first =
                terminalReceipt(
                        OperationId("operation-delivery-first"),
                        InventoryOwnershipReceiptKind.ALREADY_OWNED,
                        ownershipReceipt(account, firstItem),
                    )
                    .copy(createdAtEpochMillis = 10)
            val second =
                terminalReceipt(
                        OperationId("operation-delivery-second"),
                        InventoryOwnershipReceiptKind.ACQUIRED,
                        ownershipReceipt(account, secondItem),
                    )
                    .copy(createdAtEpochMillis = 11)
            val repository =
                DeliveryRepository(
                    inventorySnapshot(
                        account,
                        listOf(firstItem, secondItem),
                        listOf(firstItem, secondItem),
                    ),
                    listOf(second, first),
                )
            val controller = InventoryController(repository, SavedStateHandle())

            controller.start(InventoryAuthOwnership.Authenticated(account))
            var content = controller.state.value as InventoryUiState.Content
            assertEquals(InventoryFeedback.ALREADY_OWNED, content.feedback)
            assertEquals(first.receiptId, content.feedbackReceiptId)
            assertTrue(repository.acknowledged.isEmpty())

            val firstPresentationToken = controller.consumeVisibleFeedback()
            content = controller.state.value as InventoryUiState.Content
            assertEquals(listOf(first.receiptId), repository.acknowledged)
            assertEquals(InventoryFeedback.ACQUIRED, content.feedback)
            assertEquals(second.receiptId, content.feedbackReceiptId)

            controller.feedbackConsumed(firstPresentationToken)
            assertEquals(listOf(first.receiptId), repository.acknowledged)
            controller.consumeVisibleFeedback()
            content = controller.state.value as InventoryUiState.Content
            assertEquals(listOf(first.receiptId, second.receiptId), repository.acknowledged)
            assertNull(content.feedback)
            assertNull(content.feedbackReceiptId)
        }

    @Test
    fun `interruption after durable presented and before publication re-presents on recreation`() =
        runTest {
            val account = AccountId("account-a")
            val ownedItem = item("presented-interruption-item")
            val receipt =
                terminalReceipt(
                    OperationId("operation-presented-interruption"),
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, ownedItem),
                )
            val repository =
                InterruptedPresentationRepository(ownedSnapshot(account, ownedItem), receipt)
            val savedState = SavedStateHandle()
            val interruptedController = InventoryController(repository, savedState)

            val interrupted = async {
                interruptedController.start(InventoryAuthOwnership.Authenticated(account))
            }
            repository.presentedBeforePublication.await()
            interrupted.cancelAndJoin()

            val interruptedContent = interruptedController.state.value as InventoryUiState.Content
            assertNull(interruptedContent.feedbackPresentationToken)
            assertTrue(repository.acknowledged.isEmpty())

            val recreated = InventoryController(repository, savedState)
            recreated.start(InventoryAuthOwnership.Authenticated(account))
            val recreatedContent = recreated.state.value as InventoryUiState.Content
            assertEquals(receipt.receiptId, recreatedContent.feedbackReceiptId)
            assertEquals(
                InventoryReceiptDeliveryPhase.PRESENTED,
                repository.activeClaim?.deliveryPhase,
            )
            assertTrue(repository.acknowledged.isEmpty())

            recreated.consumeVisibleFeedback()
            assertEquals(listOf(receipt.receiptId), repository.acknowledged)
        }

    @Test
    fun `ack failure keeps exact presented token visible and explicit retry resumes consumed acknowledgement`() =
        runTest {
            val account = AccountId("account-a")
            val ownedItem = item("ack-failure-item")
            val receipt =
                terminalReceipt(
                    OperationId("operation-ack-failure"),
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, ownedItem),
                )
            val repository =
                DeliveryRepository(
                    ownedSnapshot(account, ownedItem),
                    listOf(receipt),
                    acknowledgementFailures = 1,
                )
            val controller = InventoryController(repository, SavedStateHandle())
            controller.start(InventoryAuthOwnership.Authenticated(account))
            val token =
                requireNotNull(
                    (controller.state.value as InventoryUiState.Content).feedbackPresentationToken
                )

            controller.feedbackConsumed(token)

            var content = controller.state.value as InventoryUiState.Content
            assertEquals(token, content.feedbackPresentationToken)
            assertTrue(repository.acknowledged.isEmpty())

            controller.retry()

            content = controller.state.value as InventoryUiState.Content
            assertNull(content.feedbackPresentationToken)
            assertEquals(listOf(receipt.receiptId), repository.acknowledged)
        }

    @Test
    fun `concurrent exact-token acknowledgements serialize one durable acknowledgement`() =
        runTest {
            val account = AccountId("account-a")
            val ownedItem = item("concurrent-ack-item")
            val receipt =
                terminalReceipt(
                    OperationId("operation-concurrent-ack"),
                    InventoryOwnershipReceiptKind.ACQUIRED,
                    ownershipReceipt(account, ownedItem),
                )
            val repository = ConcurrentAckRepository(ownedSnapshot(account, ownedItem), receipt)
            val controller = InventoryController(repository, SavedStateHandle())
            controller.start(InventoryAuthOwnership.Authenticated(account))
            val token =
                requireNotNull(
                    (controller.state.value as InventoryUiState.Content).feedbackPresentationToken
                )

            val first = async { controller.feedbackConsumed(token) }
            repository.ackStarted.await()
            val second = async { controller.feedbackConsumed(token) }
            runCurrent()
            try {
                assertEquals(1, repository.ackAttempts)
            } finally {
                repository.releaseAck.complete(Unit)
            }
            first.await()
            second.await()

            assertEquals(1, repository.ackAttempts)
            assertEquals(listOf(receipt.receiptId), repository.acknowledged)
            assertNull(
                (controller.state.value as InventoryUiState.Content).feedbackPresentationToken
            )
        }

    @Test
    fun `same process recreation re-presents a durable unacknowledged receipt without auto ack`() =
        runTest {
            val account = AccountId("account-a")
            val ownedItem = item("crash-window-item")
            val receipt =
                terminalReceipt(
                    OperationId("operation-crash-window"),
                    InventoryOwnershipReceiptKind.ALREADY_OWNED,
                    ownershipReceipt(account, ownedItem),
                )
            val repository =
                DeliveryRepository(
                    ownedSnapshot(account, ownedItem),
                    listOf(receipt),
                )
            val savedState = SavedStateHandle()
            val first = InventoryController(repository, savedState)
            first.start(InventoryAuthOwnership.Authenticated(account))
            assertEquals(
                receipt.receiptId,
                (first.state.value as InventoryUiState.Content).feedbackReceiptId,
            )

            val recreated = InventoryController(repository, savedState)
            recreated.start(InventoryAuthOwnership.Authenticated(account))

            assertTrue(repository.acknowledged.isEmpty())
            val content = recreated.state.value as InventoryUiState.Content
            assertEquals(InventoryFeedback.ALREADY_OWNED, content.feedback)
            assertEquals(receipt.receiptId, content.feedbackReceiptId)
        }

    @Test
    fun `process death does not steal an unexpired claim from the prior process`() = runTest {
        val account = AccountId("account-a")
        val ownedItem = item("process-death-item")
        val receipt =
            terminalReceipt(
                OperationId("operation-process-death"),
                InventoryOwnershipReceiptKind.ACQUIRED,
                ownershipReceipt(account, ownedItem),
            )
        val repository = DeliveryRepository(ownedSnapshot(account, ownedItem), listOf(receipt))
        val beforeDeath = InventoryController(repository, SavedStateHandle())
        beforeDeath.start(InventoryAuthOwnership.Authenticated(account))
        assertEquals(
            receipt.receiptId,
            (beforeDeath.state.value as InventoryUiState.Content).feedbackReceiptId,
        )

        val afterDeath = InventoryController(repository, SavedStateHandle())
        afterDeath.start(InventoryAuthOwnership.Authenticated(account))
        val content = afterDeath.state.value as InventoryUiState.Content
        assertNull(content.feedback)
        assertNull(content.feedbackReceiptId)
        assertTrue(repository.acknowledged.isEmpty())
    }

    @Test
    fun `section category and bounded search survive recreation while malformed saved enums do not crash`() =
        runTest {
            val account = AccountId("account-a")
            val repository = QueueRepository(snapshot(account, "furniture-a"))
            val saved = SavedStateHandle()
            val first = InventoryController(repository, saved)
            first.start(InventoryAuthOwnership.Authenticated(account))
            first.selectSection(InventorySection.SHOP)
            first.selectCategory(ItemCategory.FURNITURE)
            first.search("초록 소파")

            val recreated = InventoryController(repository, saved)
            recreated.start(InventoryAuthOwnership.Authenticated(account))
            val restored = recreated.state.value as InventoryUiState.Content
            assertEquals(InventorySection.SHOP, restored.section)
            assertEquals(ItemCategory.FURNITURE, restored.category)
            assertEquals("초록 소파", restored.searchQuery)

            val malformed =
                SavedStateHandle(
                    mapOf(
                        "inventory.section" to "DELETED",
                        "inventory.category" to "PRIVATE",
                        "inventory.search" to "x".repeat(150),
                    )
                )
            val safe = InventoryController(repository, malformed)
            safe.start(InventoryAuthOwnership.Authenticated(account))
            val content = safe.state.value as InventoryUiState.Content
            assertEquals(InventorySection.WAREHOUSE, content.section)
            assertNull(content.category)
            assertEquals(100, content.searchQuery.length)
        }

    private class ControlledCall<T>(
        val result: CompletableDeferred<T> = CompletableDeferred(),
        val nonCancellable: Boolean = false,
    ) {
        val started = CompletableDeferred<Unit>()

        suspend fun await(): T {
            started.complete(Unit)
            return if (nonCancellable) withContext(NonCancellable) { result.await() }
            else result.await()
        }
    }

    private class ControlledRepository : InventoryRepository {
        val loads = ArrayDeque<ControlledCall<InventoryLoadResult>>()
        val acquisitions = ArrayDeque<ControlledCall<InventoryAcquireResult>>()
        val claimedExpected = mutableListOf<InventoryAcquisitionTerminalReceipt>()
        var claimAttempts = 0
            private set

        var forceRefreshCalls = 0
            private set

        override suspend fun load(): InventoryLoadResult = loads.removeFirst().await()

        override suspend fun load(forceRefresh: Boolean): InventoryLoadResult {
            if (forceRefresh) forceRefreshCalls += 1
            return loads.removeFirst().await()
        }

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult =
            acquisitions.removeFirst().await()

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            val receipt = expected?.receipt ?: knownReceipts.getValue(receiptId)
            claimAttempts += 1
            claimedExpected += receipt
            return InventoryReceiptClaimResult.Claimed(receipt.claimedBy(claimant))
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult =
            InventoryReceiptPresentationResult.Presented(
                claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            )

        override suspend fun acknowledgeReceipt(claim: InventoryReceiptClaim) =
            InventoryReceiptAcknowledgement.ACKNOWLEDGED
    }

    private class DeferredRepository : InventoryRepository {
        val loads = ArrayDeque<CompletableDeferred<InventoryLoadResult>>()

        override suspend fun load() = loads.removeFirst().await()

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult = error("not used")
    }

    private class InterruptedPresentationRepository(
        private val snapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val presentedBeforePublication = CompletableDeferred<Unit>()
        val acknowledged = mutableListOf<InventoryReceiptId>()
        var activeClaim: InventoryReceiptClaim? = null
            private set

        private var interruptFirstPresentation = true

        override suspend fun load() =
            InventoryLoadResult.Ready(snapshot, false, listOf(receipt.receiptId))

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            if (receiptId != receipt.receiptId) return InventoryReceiptClaimResult.Missing
            val current = activeClaim
            if (expected?.rowVersion != null && current?.rowVersion != expected.rowVersion) {
                return InventoryReceiptClaimResult.Missing
            }
            if (current == null) {
                return InventoryReceiptClaimResult.Claimed(receipt.claimedBy(claimant)).also {
                    activeClaim = it.claim
                }
            }
            if (current.claimant == claimant) {
                return InventoryReceiptClaimResult.Claimed(current)
            }
            if (
                current.claimant.presentationToken == claimant.presentationToken &&
                    current.claimant.controllerEpoch < claimant.controllerEpoch
            ) {
                val rebound = current.copy(claimant = claimant, rowVersion = current.rowVersion + 1)
                activeClaim = rebound
                return InventoryReceiptClaimResult.Claimed(rebound)
            }
            return InventoryReceiptClaimResult.Unavailable(current.leaseExpiresAtEpochMillis)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claim != activeClaim) return InventoryReceiptPresentationResult.Mismatch
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            activeClaim = presented
            if (interruptFirstPresentation) {
                interruptFirstPresentation = false
                presentedBeforePublication.complete(Unit)
                awaitCancellation()
            }
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claim != activeClaim) return InventoryReceiptAcknowledgement.MISMATCH
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class ConcurrentAckRepository(
        private val snapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val ackStarted = CompletableDeferred<Unit>()
        val releaseAck = CompletableDeferred<Unit>()
        val acknowledged = mutableListOf<InventoryReceiptId>()
        var ackAttempts = 0
            private set

        private var claim: InventoryReceiptClaim? = null

        override suspend fun load() =
            InventoryLoadResult.Ready(
                snapshot,
                false,
                if (acknowledged.isEmpty()) listOf(receipt.receiptId) else emptyList(),
            )

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            if (receiptId != receipt.receiptId || acknowledged.isNotEmpty()) {
                return InventoryReceiptClaimResult.Missing
            }
            val current = claim ?: receipt.claimedBy(claimant).also { claim = it }
            return InventoryReceiptClaimResult.Claimed(current)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claim != this.claim) return InventoryReceiptPresentationResult.Mismatch
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            this.claim = presented
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claim != this.claim) return InventoryReceiptAcknowledgement.MISMATCH
            ackAttempts += 1
            ackStarted.complete(Unit)
            releaseAck.await()
            if (acknowledged.isNotEmpty()) {
                return InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED
            }
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class DeliveryRepository(
        private val snapshot: InventorySnapshot,
        receipts: List<InventoryAcquisitionTerminalReceipt>,
        private var acknowledgementFailures: Int = 0,
    ) : InventoryRepository {
        private val pending = receipts.sortedBy { it.createdAtEpochMillis }.toMutableList()
        private val claims = mutableMapOf<InventoryReceiptId, InventoryReceiptClaim>()
        val acknowledged = mutableListOf<InventoryReceiptId>()

        override suspend fun load() =
            InventoryLoadResult.Ready(snapshot, false, pending.map { it.receiptId })

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            val receipt =
                pending.singleOrNull { it.receiptId == receiptId }
                    ?: return InventoryReceiptClaimResult.Missing
            val current = claims[receipt.receiptId]
            if (current != null && current.claimant == claimant) {
                return InventoryReceiptClaimResult.Claimed(current)
            }
            if (
                current != null &&
                    (current.claimant.presentationToken != claimant.presentationToken ||
                        current.claimant.controllerEpoch >= claimant.controllerEpoch)
            ) {
                return InventoryReceiptClaimResult.Unavailable(current.leaseExpiresAtEpochMillis)
            }
            val claim = receipt.claimedBy(claimant, (current?.rowVersion ?: 0) + 1)
            claims[receipt.receiptId] = claim
            return InventoryReceiptClaimResult.Claimed(claim)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claims[claim.receipt.receiptId] != claim) {
                return InventoryReceiptPresentationResult.Mismatch
            }
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            claims[claim.receipt.receiptId] = presented
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claims[claim.receipt.receiptId] != claim) {
                return InventoryReceiptAcknowledgement.MISMATCH
            }
            if (acknowledgementFailures > 0) {
                acknowledgementFailures -= 1
                return InventoryReceiptAcknowledgement.DATABASE_FAILURE
            }
            val removed = pending.removeAll { it.receiptId == claim.receipt.receiptId }
            if (!removed) return InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class BarrierBatchClaimRepository(
        snapshot: InventorySnapshot,
        receipts: List<InventoryAcquisitionTerminalReceipt>,
    ) : InventoryRepository {
        val secondClaimStarted = CompletableDeferred<Unit>()
        val releaseSecondClaim = CompletableDeferred<Unit>()
        val acknowledged = mutableListOf<InventoryReceiptId>()
        private var snapshot = snapshot
        private val receipts = receipts.toMutableList()
        private val claims = mutableMapOf<InventoryReceiptId, InventoryReceiptClaim>()
        private var initialLoad = true
        private var claimAttempts = 0

        override suspend fun load(): InventoryLoadResult {
            if (initialLoad) {
                initialLoad = false
                return InventoryLoadResult.Ready(snapshot, false)
            }
            return InventoryLoadResult.Ready(
                snapshot,
                false,
                receipts.filterNot { it.receiptId in acknowledged }.map { it.receiptId },
            )
        }

        fun addReceipt(receipt: InventoryAcquisitionTerminalReceipt) {
            receipts += receipt
        }

        fun switchOwner(snapshot: InventorySnapshot) {
            this.snapshot = snapshot
            receipts.clear()
        }

        fun expireClaims() {
            claims.clear()
        }

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            val receipt =
                receipts.singleOrNull { it.receiptId == receiptId }
                    ?: expected?.receipt
                    ?: return InventoryReceiptClaimResult.Missing
            val attempt = ++claimAttempts
            val existing = claims[receipt.receiptId]
            val reserved = existing ?: receipt.claimedBy(claimant)
            if (existing == null) claims[receipt.receiptId] = reserved
            if (attempt == 1) {
                secondClaimStarted.complete(Unit)
                withContext(NonCancellable) { releaseSecondClaim.await() }
            }
            if (receipt.receiptId in acknowledged) return InventoryReceiptClaimResult.Missing
            val current =
                claims[receipt.receiptId]
                    ?: return InventoryReceiptClaimResult.Unavailable(Long.MAX_VALUE)
            if (current.claimant.presentationToken != claimant.presentationToken) {
                return InventoryReceiptClaimResult.Unavailable(current.leaseExpiresAtEpochMillis)
            }
            return InventoryReceiptClaimResult.Claimed(current)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claims[claim.receipt.receiptId] != claim) {
                return InventoryReceiptPresentationResult.Mismatch
            }
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            claims[claim.receipt.receiptId] = presented
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claims[claim.receipt.receiptId] != claim) {
                return InventoryReceiptAcknowledgement.MISMATCH
            }
            if (claim.receipt.receiptId in acknowledged) {
                return InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED
            }
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class CancelledWinnerRepository(
        private val snapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val claimPersisted = CompletableDeferred<Unit>()
        val acknowledged = mutableListOf<InventoryReceiptId>()
        var deliveryPhase: InventoryReceiptDeliveryPhase? = null
            private set

        private var initialLoad = true
        private var interruptFirstClaim = true
        private var claim: InventoryReceiptClaim? = null

        override suspend fun load() =
            InventoryLoadResult.Ready(
                    snapshot,
                    false,
                    if (initialLoad) emptyList() else listOf(receipt.receiptId),
                )
                .also { initialLoad = false }

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            if (receiptId != receipt.receiptId) return InventoryReceiptClaimResult.Missing
            val current = claim ?: receipt.claimedBy(claimant).also { claim = it }
            deliveryPhase = current.deliveryPhase
            if (interruptFirstClaim) {
                interruptFirstClaim = false
                claimPersisted.complete(Unit)
                awaitCancellation()
            }
            return InventoryReceiptClaimResult.Claimed(current)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claim != this.claim) return InventoryReceiptPresentationResult.Mismatch
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            this.claim = presented
            deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claim != this.claim) return InventoryReceiptAcknowledgement.MISMATCH
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class StaleThenDeliveryRepository(
        private val snapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val acknowledged = mutableListOf<InventoryReceiptId>()
        var claimAttempts = 0
            private set

        private var claim: InventoryReceiptClaim? = null
        private var loads = 0

        override suspend fun load() =
            if (loads++ == 0) {
                InventoryLoadResult.Ready(snapshot, false, listOf(receipt.receiptId))
            } else {
                InventoryLoadResult.Ready(
                    snapshot,
                    false,
                    receiptCandidates = emptyList(),
                    receiptCandidatesAuthoritative = false,
                )
            }

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            if (receiptId != receipt.receiptId) return InventoryReceiptClaimResult.Missing
            claimAttempts += 1
            if (claimAttempts == 1) return InventoryReceiptClaimResult.Stale
            val current = claim ?: receipt.claimedBy(claimant).also { claim = it }
            return InventoryReceiptClaimResult.Claimed(current)
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claim != this.claim) return InventoryReceiptPresentationResult.Mismatch
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            this.claim = presented
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claim != this.claim) return InventoryReceiptAcknowledgement.MISMATCH
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class ConcurrentRetryRepository(
        private val snapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val claimStarted = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val acknowledged = mutableListOf<InventoryReceiptId>()
        var claimAttempts = 0
            private set

        var deliveryPhase: InventoryReceiptDeliveryPhase? = null
            private set

        private var initialLoad = true
        private var activeClaim: InventoryReceiptClaim? = null

        override suspend fun load(): InventoryLoadResult =
            InventoryLoadResult.Ready(
                    snapshot,
                    false,
                    if (initialLoad) emptyList() else listOf(receipt.receiptId),
                )
                .also { initialLoad = false }

        override suspend fun acquire(request: InventoryAcquireRequest) = error("not used")

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            if (receiptId != receipt.receiptId) return InventoryReceiptClaimResult.Missing
            claimAttempts += 1
            claimStarted.complete(Unit)
            releaseClaim.await()
            val current = activeClaim
            if (current != null) return InventoryReceiptClaimResult.Claimed(current)
            return InventoryReceiptClaimResult.Claimed(receipt.claimedBy(claimant)).also {
                activeClaim = it.claim
                deliveryPhase = InventoryReceiptDeliveryPhase.CLAIMED
            }
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claim != activeClaim) return InventoryReceiptPresentationResult.Mismatch
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            activeClaim = presented
            deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (claim != activeClaim) return InventoryReceiptAcknowledgement.MISMATCH
            acknowledged += claim.receipt.receiptId
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class DelayedReloadLedgerRepository(
        private val initialSnapshot: InventorySnapshot,
        private val acquiredSnapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val reloadStarted = CompletableDeferred<Unit>()
        val releaseReload = CompletableDeferred<Unit>()
        var acknowledgements = 0
        private var loads = 0
        private var claim: InventoryReceiptClaim? = null
        private var acknowledged = false

        override suspend fun load(): InventoryLoadResult {
            if (loads++ == 0) return InventoryLoadResult.Ready(initialSnapshot, false)
            reloadStarted.complete(Unit)
            releaseReload.await()
            return InventoryLoadResult.Ready(acquiredSnapshot, false, listOf(receipt.receiptId))
        }

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult =
            InventoryAcquireResult.Success(receipt.receipt)

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            if (receiptId != receipt.receiptId) return InventoryReceiptClaimResult.Missing
            if (acknowledged) return InventoryReceiptClaimResult.Missing
            val current = claim
            if (current != null) return InventoryReceiptClaimResult.Claimed(current)
            return InventoryReceiptClaimResult.Claimed(receipt.claimedBy(claimant)).also {
                claim = it.claim
            }
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult {
            if (claim != this.claim) return InventoryReceiptPresentationResult.Mismatch
            val presented = claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            this.claim = presented
            return InventoryReceiptPresentationResult.Presented(presented)
        }

        override suspend fun acknowledgeReceipt(
            claim: InventoryReceiptClaim
        ): InventoryReceiptAcknowledgement {
            if (acknowledged || claim != this.claim) {
                return InventoryReceiptAcknowledgement.MISMATCH
            }
            acknowledged = true
            acknowledgements += 1
            return InventoryReceiptAcknowledgement.ACKNOWLEDGED
        }
    }

    private class DelayedAcquireAcknowledgedRepository(
        private val snapshot: InventorySnapshot,
        private val receipt: InventoryAcquisitionTerminalReceipt,
    ) : InventoryRepository {
        val acquireStarted = CompletableDeferred<Unit>()
        val releaseAcquire = CompletableDeferred<Unit>()
        var claimAttempts = 0
            private set

        private var acknowledged = false

        override suspend fun load() = InventoryLoadResult.Ready(snapshot, false)

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
            acquireStarted.complete(Unit)
            releaseAcquire.await()
            return InventoryAcquireResult.Success(receipt.receipt)
        }

        fun acknowledgeBeforeResponse() {
            acknowledged = true
        }

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            claimAttempts += 1
            if (receiptId != receipt.receiptId) return InventoryReceiptClaimResult.Missing
            return if (acknowledged) {
                InventoryReceiptClaimResult.Missing
            } else {
                InventoryReceiptClaimResult.Claimed(receipt.claimedBy(claimant))
            }
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult = error("not used")
    }

    private class QueueRepository(private var snapshot: InventorySnapshot) : InventoryRepository {
        val results = ArrayDeque<InventoryAcquireResult>()
        val operations = mutableListOf<OperationId>()

        override suspend fun load() = InventoryLoadResult.Ready(snapshot, false)

        override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
            operations += request.operationId
            return results.removeFirst().also { result ->
                if (result is InventoryAcquireResult.Success) {
                    snapshot =
                        snapshot.copy(
                            owned =
                                listOf(
                                    OwnedInventoryItem(
                                        result.receipt.itemId,
                                        result.receipt.acquiredAt,
                                        false,
                                        result.receipt.ownershipRevision,
                                    )
                                )
                        )
                }
            }
        }

        override suspend fun claimForPresentation(
            receiptId: InventoryReceiptId,
            expected: InventoryReceiptPresentationExpectation?,
            claimant: InventoryReceiptClaimant,
        ): InventoryReceiptClaimResult {
            val receipt = expected?.receipt ?: knownReceipts.getValue(receiptId)
            return InventoryReceiptClaimResult.Claimed(receipt.claimedBy(claimant))
        }

        override suspend fun markReceiptPresented(
            claim: InventoryReceiptClaim
        ): InventoryReceiptPresentationResult =
            InventoryReceiptPresentationResult.Presented(
                claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
            )

        override suspend fun acknowledgeReceipt(claim: InventoryReceiptClaim) =
            InventoryReceiptAcknowledgement.ACKNOWLEDGED
    }

    private suspend fun InventoryController.consumeVisibleFeedback():
        InventoryFeedbackPresentationToken {
        val token =
            requireNotNull((state.value as InventoryUiState.Content).feedbackPresentationToken)
        feedbackConsumed(token)
        return token
    }

    private fun <T> completed(value: T) = CompletableDeferred(value)

    private fun success(account: AccountId, item: InventoryItem) =
        InventoryAcquireResult.Success(ownershipReceipt(account, item))

    private fun ownershipReceipt(account: AccountId, item: InventoryItem) =
        InventoryOwnershipReceipt(
            account,
            item.id,
            item.revision,
            Revision(1),
            Instant.parse("2026-08-20T00:00:00Z"),
        )

    private fun terminalReceipt(
        operationId: OperationId,
        kind: InventoryOwnershipReceiptKind,
        receipt: InventoryOwnershipReceipt,
    ) =
        InventoryAcquisitionTerminalReceipt(
                receipt.accountId,
                receipt.itemId,
                operationId,
                kind,
                receipt,
            )
            .also { knownReceipts[it.receiptId] = it }

    private fun inventorySnapshot(
        account: AccountId,
        catalog: List<InventoryItem>,
        owned: List<InventoryItem> = emptyList(),
    ) =
        InventorySnapshot(
            account,
            catalog,
            owned.map {
                OwnedInventoryItem(
                    it.id,
                    Instant.parse("2026-08-20T00:01:00Z"),
                    false,
                    Revision(1),
                )
            },
            1,
            Instant.parse("2026-08-20T00:00:00Z"),
        )

    private fun snapshot(account: AccountId, itemId: String) =
        InventorySnapshot(
            account,
            listOf(item(itemId)),
            emptyList(),
            1,
            Instant.parse("2026-08-20T00:00:00Z"),
        )

    private fun ownedSnapshot(account: AccountId, item: InventoryItem) =
        snapshot(account, item.id.value)
            .copy(
                owned =
                    listOf(
                        OwnedInventoryItem(
                            item.id,
                            Instant.parse("2026-08-20T00:01:00Z"),
                            false,
                            Revision(1),
                        )
                    )
            )

    private fun item(itemId: String) =
        InventoryItem(
            ItemId(itemId),
            itemId,
            "$itemId description",
            ItemCategory.DECORATION,
            "items/$itemId.png",
            null,
            Revision(1),
            Instant.parse("2026-08-20T00:00:00Z"),
        )

    private companion object {
        val knownReceipts = mutableMapOf<InventoryReceiptId, InventoryAcquisitionTerminalReceipt>()

        fun InventoryAcquisitionTerminalReceipt.claimedBy(
            claimant: InventoryReceiptClaimant,
            rowVersion: Long = 1,
        ) = InventoryReceiptClaim(this, claimant, rowVersion, Long.MAX_VALUE)
    }
}
