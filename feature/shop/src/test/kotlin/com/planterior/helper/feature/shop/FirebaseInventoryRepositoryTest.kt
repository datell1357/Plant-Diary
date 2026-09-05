package com.planterior.helper.feature.shop

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.verifiedAuthoritativeInventory
import com.planterior.helper.core.database.CachedShopItemEntity
import com.planterior.helper.core.database.InventorySnapshotWatermarkEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirebaseInventoryRepositoryTest {
    private lateinit var database: PlanteriorDatabase
    private lateinit var remote: FakeInventoryRemote
    private var queryObserver: ((String) -> Unit)? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryCallback(
                    object : androidx.room.RoomDatabase.QueryCallback {
                        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                            queryObserver?.invoke(sqlQuery)
                        }
                    },
                    java.util.concurrent.Executor { it.run() },
                )
                .build()
        remote = FakeInventoryRemote()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `auth transition during authoritative transaction rolls back inventory writes`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val transition = CompletableDeferred<Unit>()
        queryObserver = { sql ->
            if (sql.startsWith("INSERT") && sql.contains("last_sync")) {
                queryObserver = null
                repository.onAccountChanged(AccountId("account-b"))
                repository.onAccountChanged(AccountId("account-a"))
                transition.complete(Unit)
            }
        }
        val result = runCatching { repository.load(forceRefresh = true) }
        assertTrue(transition.isCompleted)
        assertTrue(
            result.exceptionOrNull() is CancellationException ||
                result.getOrNull() == InventoryLoadResult.Forbidden
        )
        assertEquals(null, database.cacheDao().inventorySnapshotWatermark("account-a"))
        assertTrue(database.cacheDao().shopItems("account-a").isEmpty())
        repository.close()
    }

    @Test
    fun `acquisition supersedes an active pre-acquisition read`() = runTest {
        val gate = CompletableDeferred<Unit>()
        remote.loadGates[0] = gate
        remote.nonCancellableLoads += 0
        val repository = FirebaseInventoryRepository(database, remote)
        val old = async { repository.load() }
        assertEquals(0, remote.loadInvocations.receive())
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(
            repository.acquire(acquisitionRequest("inflight-acquire"))
                is InventoryAcquireResult.Success
        )
        val refreshed = repository.load(forceRefresh = true) as InventoryLoadResult.Ready
        assertEquals(1, remote.loadInvocations.receive())
        assertEquals(listOf("free-item"), refreshed.snapshot.owned.map { it.itemId.value })
        gate.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        val cached = repository.load() as InventoryLoadResult.Ready
        assertEquals(refreshed.snapshot, cached.snapshot)
        repository.close()
    }

    @Test
    fun `verified cache is immediate and force refresh bypasses freshness`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val first = repository.load() as InventoryLoadResult.Ready
        assertFalse(first.stale)

        val cached = repository.load() as InventoryLoadResult.Ready
        assertFalse(cached.stale)
        assertEquals(0, remote.loadInvocations.tryReceive().getOrNull())
        assertTrue(remote.loadInvocations.tryReceive().isFailure)

        repository.load(forceRefresh = true)
        assertEquals(1, remote.loadInvocations.tryReceive().getOrNull())
    }

    @Test
    fun `cached read preserves durable receipt candidates`() = runTest {
        val request = acquisitionRequest("cache-receipt")
        remote.acquireOutcome =
            RemoteInventoryAcquireResult.Acquired(
                request.accountId,
                request.itemId,
                request.expectedCatalogRevision,
                Revision(1),
                Instant.parse("2026-08-12T00:00:00Z"),
                testCatalogMediaIdentity(request.itemId.value, "cache-receipt"),
            )
        val repository = FirebaseInventoryRepository(database, remote)
        repository.acquire(request)

        val loaded = repository.load() as InventoryLoadResult.Ready
        assertEquals(
            listOf(InventoryReceiptId("account-a/cache-receipt")),
            loaded.receiptCandidates,
        )
        val cached = repository.load() as InventoryLoadResult.Ready
        assertEquals(loaded.receiptCandidates, cached.receiptCandidates)
    }

    @Test
    fun `ordinary and forced concurrent loads share one remote flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        remote.loadGates[0] = gate
        val repository = FirebaseInventoryRepository(database, remote)
        val ordinary = async { repository.load() }
        val forced = async { repository.load(forceRefresh = true) }
        val invocation = remote.loadInvocations.receive()
        assertEquals(0, invocation)
        assertTrue(remote.loadInvocations.tryReceive().isFailure)
        gate.complete(Unit)
        assertTrue(ordinary.await() is InventoryLoadResult.Ready)
        assertTrue(forced.await() is InventoryLoadResult.Ready)
        assertTrue(remote.loadInvocations.tryReceive().isFailure)
    }

    @Test
    fun `freshness expiry returns stale cache and auth A-B-A invalidates freshness`() = runTest {
        var elapsed = 0L
        val repository =
            FirebaseInventoryRepository(database, remote, elapsedRealtime = { elapsed })
        repository.load()
        elapsed = 30_001L
        val stale = repository.load() as InventoryLoadResult.Ready
        assertTrue(stale.stale)
        assertTrue(stale.refreshRequired)
        remote.loadSnapshots[1] = snapshot(AccountId("account-a"), "after-expiry")
        repository.onAccountChanged(AccountId("account-b"))
        repository.onAccountChanged(AccountId("account-a"))
        val refreshed = repository.load(forceRefresh = true) as InventoryLoadResult.Ready
        assertEquals("after-expiry", refreshed.snapshot.catalog.single().id.value)
    }

    @Test
    fun `cancelling one waiter does not cancel shared remote flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        remote.loadGates[0] = gate
        val repository = FirebaseInventoryRepository(database, remote)
        val first = async { repository.load() }
        remote.loadInvocations.receive()
        val second = async { repository.load(forceRefresh = true) }
        runCurrent()
        second.cancel()
        gate.complete(Unit)
        assertTrue(first.await() is InventoryLoadResult.Ready)
        assertTrue(remote.loadInvocations.tryReceive().isFailure)
    }

    @Test
    fun `auth epoch replacement prevents late A response from applying after A-B-A`() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        remote.loadGates[0] = oldGate
        remote.nonCancellableLoads += 0
        remote.loadGates[1] = newGate
        remote.loadSnapshots[1] = snapshot(AccountId("account-a"), "new-a")
        val repository = FirebaseInventoryRepository(database, remote)
        val old = async { repository.load(forceRefresh = true) }
        remote.loadInvocations.receive()
        repository.onAccountChanged(AccountId("account-b"))
        repository.onAccountChanged(AccountId("account-a"))
        val replacement = async { repository.load(forceRefresh = true) }
        assertEquals(1, remote.loadInvocations.receive())
        newGate.complete(Unit)
        val result = replacement.await() as InventoryLoadResult.Ready
        assertEquals("new-a", result.snapshot.catalog.single().id.value)
        oldGate.complete(Unit)
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        val durable = database.cacheDao().verifiedAuthoritativeInventory(AccountId("account-a"))
        assertEquals("new-a", requireNotNull(durable).catalog.single().itemId.value)
        repository.close()
    }

    @Test
    fun `migrated generation zero is purged then first authority replaces live and restarts offline`() =
        runTest {
            val account = AccountId("account-a")
            database.cacheDao().upsertShopItems(listOf(cachedItem(account, "legacy-item")))
            database
                .cacheDao()
                .upsertInventorySnapshotWatermark(
                    InventorySnapshotWatermarkEntity(
                        accountId = account.value,
                        generation = 0,
                        snapshotHash = "0".repeat(64),
                        registeredPlantCount = 0,
                        loadedAtEpochMillis = 0,
                        partial = true,
                        verified = false,
                    )
                )
            val authoritative =
                InventorySnapshot(
                    accountId = account,
                    catalog = listOf(inventoryItem("authoritative-item")),
                    owned = emptyList(),
                    registeredPlantCount = 0,
                    loadedAt = Instant.ofEpochMilli(2),
                    generation = 1,
                )
            val bootstrapRemote = BootstrapRemote(account, authoritative)
            val controller =
                InventoryController(
                    FirebaseInventoryRepository(database, bootstrapRemote),
                    androidx.lifecycle.SavedStateHandle(),
                )

            controller.start(InventoryAuthOwnership.Authenticated(account))
            assertEquals(InventoryUiState.Error(account), controller.state.value)
            assertTrue(database.cacheDao().shopItems(account.value).isEmpty())
            assertEquals(null, database.cacheDao().inventorySnapshotWatermark(account.value))

            controller.retry()

            val adopted = (controller.state.value as InventoryUiState.Content).snapshot
            assertEquals(1L, adopted.generation)
            assertTrue(adopted.verified)
            assertEquals(listOf("authoritative-item"), adopted.catalog.map { it.id.value })
            assertEquals(
                listOf("authoritative-item"),
                database.cacheDao().shopItems(account.value).map { it.itemId },
            )

            bootstrapRemote.offline = true
            val restarted =
                InventoryController(
                    FirebaseInventoryRepository(database, bootstrapRemote),
                    androidx.lifecycle.SavedStateHandle(),
                )
            restarted.start(InventoryAuthOwnership.Authenticated(account))
            val restored = (restarted.state.value as InventoryUiState.Content).snapshot
            assertEquals(1L, restored.generation)
            assertTrue(restored.verified)
            assertEquals(listOf("authoritative-item"), restored.catalog.map { it.id.value })

            bootstrapRemote.accountId = AccountId("account-b")
            restarted.start(InventoryAuthOwnership.Authenticated(AccountId("account-b")))
            assertEquals(InventoryUiState.Error(AccountId("account-b")), restarted.state.value)
        }

    @Test
    fun `authoritative load is cached and network failure returns only the same owner stale snapshot`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val ready = repository.load() as InventoryLoadResult.Ready
            assertEquals(false, ready.stale)
            assertEquals(
                listOf("free-item", "plant-item"),
                ready.snapshot.catalog.map { it.id.value },
            )

            remote.loadFailure = IOException("offline")
            val stale = repository.load(forceRefresh = true) as InventoryLoadResult.Ready
            assertEquals(true, stale.stale)
            assertEquals(AccountId("account-a"), stale.snapshot.accountId)

            remote.accountId = AccountId("account-b")
            assertEquals(InventoryLoadResult.Failed, repository.load())
            assertTrue(database.cacheDao().ownedItems("account-b").isEmpty())
        }

    @Test
    fun `bootstrap reconciles remote missing partial and public catalog changes through verified Room state`() =
        runTest {
            val account = AccountId("account-a")
            database.cacheDao().upsertShopItems(listOf(cachedItem(account, "legacy-item")))
            database
                .cacheDao()
                .upsertInventorySnapshotWatermark(
                    InventorySnapshotWatermarkEntity(
                        account.value,
                        0,
                        "0".repeat(64),
                        0,
                        0,
                        true,
                        false,
                    )
                )
            remote.loadedSnapshot =
                InventorySnapshot(account, emptyList(), emptyList(), 0, Instant.ofEpochMilli(1))
            val repository = FirebaseInventoryRepository(database, remote)

            val missing = repository.load() as InventoryLoadResult.Ready
            assertTrue(missing.snapshot.verified)
            assertTrue(missing.snapshot.catalog.isEmpty())

            remote.loadedSnapshot =
                InventorySnapshot(
                    account,
                    listOf(inventoryItem("public-item")),
                    emptyList(),
                    0,
                    Instant.ofEpochMilli(2),
                    partial = true,
                    generation = 2,
                )
            val partial = repository.load(forceRefresh = true) as InventoryLoadResult.Partial
            assertTrue(partial.snapshot.verified)
            assertEquals(listOf("public-item"), partial.snapshot.catalog.map { it.id.value })

            remote.loadedSnapshot =
                InventorySnapshot(
                    account,
                    emptyList(),
                    emptyList(),
                    0,
                    Instant.ofEpochMilli(3),
                    generation = 3,
                )
            val unpublished = repository.load(forceRefresh = true) as InventoryLoadResult.Ready
            assertTrue(unpublished.snapshot.verified)
            assertTrue(unpublished.snapshot.catalog.isEmpty())
            assertTrue(database.cacheDao().shopItems(account.value).isEmpty())
        }

    @Test
    fun `same generation conflict after verified bootstrap returns only current verified cache`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val initial = repository.load() as InventoryLoadResult.Ready
            assertTrue(initial.snapshot.verified)
            remote.loadedSnapshot =
                InventorySnapshot(
                    initial.snapshot.accountId,
                    listOf(inventoryItem("conflicting-item")),
                    emptyList(),
                    0,
                    initial.snapshot.loadedAt.plusSeconds(1),
                    generation = initial.snapshot.generation,
                )

            val result = repository.load(forceRefresh = true) as InventoryLoadResult.Ready

            assertTrue(result.stale)
            assertTrue(result.snapshot.verified)
            assertEquals(
                listOf("free-item", "plant-item"),
                result.snapshot.catalog.map { it.id.value },
            )
        }

    @Test
    fun `delayed older same-owner load returns the newer Room snapshot from another writer`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val initial = (repository.load() as InventoryLoadResult.Ready).snapshot
            assertEquals(0, remote.loadInvocations.receive())
            val older = authoritativeOwnedSnapshot(initial, listOf("free-item"), generation = 2)
            val newer =
                authoritativeOwnedSnapshot(
                    initial,
                    listOf("free-item", "plant-item"),
                    generation = 3,
                )
            val olderGate = CompletableDeferred<Unit>()
            remote.loadSnapshots[1] = older
            remote.loadSnapshots[2] = newer
            remote.loadGates[1] = olderGate

            val delayed = async { repository.load(forceRefresh = true) }
            assertEquals(1, remote.loadInvocations.receive())
            val otherWriter = FirebaseInventoryRepository(database, remote)
            val latest = async { otherWriter.load(forceRefresh = true) }
            assertEquals(2, remote.loadInvocations.receive())
            assertEquals(3L, (latest.await() as InventoryLoadResult.Ready).snapshot.generation)

            olderGate.complete(Unit)
            val delayedResult = delayed.await() as InventoryLoadResult.Ready
            assertEquals(3L, delayedResult.snapshot.generation)
            assertEquals(
                listOf("free-item", "plant-item"),
                delayedResult.snapshot.owned.map { it.itemId.value }.sorted(),
            )
            assertEquals(
                3L,
                database.cacheDao().inventorySnapshotWatermark("account-a")?.generation,
            )
        }

    @Test
    fun `partial unavailable ownership survives offline process recreation without entering shop`() =
        runTest {
            val unavailable =
                OwnedInventoryItem(
                    ItemId("retired-background"),
                    Instant.parse("2026-08-12T00:00:00Z"),
                    applied = true,
                    revision = Revision(4),
                    availability = InventoryItemAvailability.UNAVAILABLE,
                    catalogSnapshot =
                        OwnedCatalogSnapshot(
                            "은퇴한 벽지",
                            ItemCategory.BACKGROUND,
                            "catalog-assets/retired-background/preview.webp",
                            Revision(3),
                        ),
                )
            remote.loadedSnapshot =
                InventorySnapshot(
                    AccountId("account-a"),
                    listOf(
                        InventoryItem(
                            ItemId("free-item"),
                            "free-item",
                            "free-item 설명",
                            ItemCategory.DECORATION,
                            "catalog-assets/free-item/preview.webp",
                            null,
                            Revision(2),
                            Instant.EPOCH,
                        )
                    ),
                    listOf(unavailable),
                    0,
                    Instant.parse("2026-08-12T01:00:00Z"),
                    partial = true,
                )
            val first = FirebaseInventoryRepository(database, remote).load()
            assertTrue(first is InventoryLoadResult.Partial && !first.stale)
            val partialSnapshot = (first as InventoryLoadResult.Partial).snapshot
            val shopEntries = InventoryPolicy.shopEntries(partialSnapshot, null)
            val warehouseEntries = InventoryPolicy.warehouseEntries(partialSnapshot, null)
            assertEquals(0, shopEntries.count { it.unavailable })
            assertEquals(1, warehouseEntries.count { it.unavailable })
            assertEquals(
                ItemCategory.BACKGROUND,
                warehouseEntries.single { it.unavailable }.category,
            )

            remote.loadFailure = IOException("offline")
            val recreated = FirebaseInventoryRepository(database, remote).load()
            assertTrue(recreated is InventoryLoadResult.Partial && recreated.stale)
            val cached = (recreated as InventoryLoadResult.Partial).snapshot.owned.single()
            assertEquals("은퇴한 벽지", cached.catalogSnapshot?.name)
            assertEquals(true, cached.applied)
        }

    @Test
    fun `response loss persists exact operation and restart replay reconciles one ownership`() =
        runTest {
            val operationId = OperationId("inventory-operation-0001")
            val request =
                InventoryAcquireRequest(
                    AccountId("account-a"),
                    ItemId("free-item"),
                    Revision(2),
                    operationId,
                )
            remote.acquireOutcome =
                RemoteInventoryAcquireResult.Acquired(
                    AccountId("account-a"),
                    ItemId("free-item"),
                    Revision(2),
                    Revision(1),
                    Instant.parse("2026-08-12T00:00:00Z"),
                    testCatalogMediaIdentity("free-item", "response-loss"),
                )
            remote.loseFirstResponse = true
            val first = FirebaseInventoryRepository(database, remote).acquire(request)
            assertEquals(
                InventoryAcquireResult.Failure(InventoryFailure.NETWORK, operationId),
                first,
            )
            val pendingOperation = database.inventoryDao().pendingOperations("account-a").single()

            val restarted = FirebaseInventoryRepository(database, remote)
            val loaded = restarted.load() as InventoryLoadResult.Ready
            assertEquals(listOf("free-item"), loaded.snapshot.owned.map { it.itemId.value })
            assertEquals(
                listOf(InventoryReceiptId("${request.accountId.value}/${operationId.value}")),
                loaded.receiptCandidates,
            )
            assertEquals(0, database.inventoryDao().pendingOperations("account-a").size)
            val completed =
                requireNotNull(database.inventoryDao().operation("account-a", operationId.value))
            assertEquals("COMPLETED", completed.state)
            assertTrue(completed.result?.startsWith("ACQUIRED|") == true)
            assertEquals(2, remote.acquireRequests.size)
            assertEquals(remote.acquireRequests[0], remote.acquireRequests[1])
        }

    @Test
    fun `completed already owned receipt survives restart until explicit UI acknowledgement`() =
        runTest {
            val request = acquisitionRequest("inventory-operation-completed-restart")
            val outcome =
                RemoteInventoryAcquireResult.AlreadyOwned(
                    request.accountId,
                    request.itemId,
                    request.expectedCatalogRevision,
                    Revision(7),
                    Instant.parse("2026-08-12T00:07:00Z"),
                    testCatalogMediaIdentity(request.itemId.value, "completed-restart"),
                )
            remote.acquireOutcome = outcome
            val first = FirebaseInventoryRepository(database, remote)
            assertEquals(
                InventoryAcquireResult.AlreadyOwned(outcome.receiptForTest()),
                first.acquire(request),
            )
            assertEquals("COMPLETED", operation(request).state)

            val restarted = FirebaseInventoryRepository(database, remote)
            val loaded = restarted.load() as InventoryLoadResult.Ready
            val delivered = loaded.receiptCandidates.single()
            assertEquals(
                InventoryReceiptId("${request.accountId.value}/${request.operationId.value}"),
                delivered,
            )
            assertTrue(operation(request).result?.startsWith("ALREADY_OWNED|") == true)
            val claim =
                restarted.present(
                    (restarted.claimReceipt(delivered, claimant("restart-process", 1, 1))
                            as InventoryReceiptClaimResult.Claimed)
                        .claim
                )
            val pendingAcknowledgement = restarted.consume(claim)
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                restarted.acknowledgeReceipt(pendingAcknowledgement),
            )
            assertTrue((restarted.load() as InventoryLoadResult.Ready).receiptCandidates.isEmpty())
            assertEquals(
                InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED,
                restarted.acknowledgeReceipt(pendingAcknowledgement),
            )
        }

    @Test
    fun `claim is atomic and acknowledgement accepts only the exact row-versioned claim`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-atomic-claim")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receipt =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val firstClaimant = claimant("process-a", 1, 1)
            val secondClaimant = claimant("process-b", 1, 1)

            val attempts =
                listOf(
                        async { repository.claimReceipt(receipt, firstClaimant) },
                        async { repository.claimReceipt(receipt, secondClaimant) },
                    )
                    .awaitAll()
            val claim =
                repository.present(
                    attempts.filterIsInstance<InventoryReceiptClaimResult.Claimed>().single().claim
                )
            assertEquals(1, attempts.count { it is InventoryReceiptClaimResult.Unavailable })
            assertEquals(
                InventoryReceiptAcknowledgement.MISMATCH,
                repository.acknowledgeReceipt(claim.copy(rowVersion = claim.rowVersion - 1)),
            )
            val pendingAcknowledgement = repository.consume(claim)
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                repository.acknowledgeReceipt(pendingAcknowledgement),
            )
            assertEquals(
                InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED,
                repository.acknowledgeReceipt(pendingAcknowledgement),
            )
            assertTrue((repository.load() as InventoryLoadResult.Ready).receiptCandidates.isEmpty())
        }

    @Test
    fun `claimed receipt cannot acknowledge before durable presented transition`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val request = acquisitionRequest("inventory-operation-presented-gate")
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
        val receiptId = (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
        val claim =
            (repository.claimReceipt(receiptId, claimant("process-a", 1, 1))
                    as InventoryReceiptClaimResult.Claimed)
                .claim

        assertEquals(InventoryReceiptDeliveryPhase.CLAIMED, claim.deliveryPhase)
        assertEquals("CLAIMED", operation(request).feedbackDeliveryState)
        assertEquals(
            InventoryReceiptAcknowledgement.MISMATCH,
            repository.acknowledgeReceipt(claim),
        )

        val presented = repository.present(claim)
        assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, presented.deliveryPhase)
        assertEquals(claim.rowVersion, presented.rowVersion)
        assertEquals("PRESENTED", operation(request).feedbackDeliveryState)
        assertEquals(
            listOf(receiptId),
            (repository.load() as InventoryLoadResult.Ready).receiptCandidates,
        )
    }

    @Test
    fun `presented receipt rebinds on recreation and only the latest stable token can acknowledge`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-presented-rebind")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receiptId =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val presented =
                repository.present(
                    (repository.claimReceipt(receiptId, claimant("process-a", 1, 1))
                            as InventoryReceiptClaimResult.Claimed)
                        .claim
                )

            val rebound =
                (repository.claimReceipt(receiptId, claimant("process-a", 2, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim
            assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, rebound.deliveryPhase)
            assertTrue(rebound.rowVersion > presented.rowVersion)
            assertEquals(
                InventoryReceiptAcknowledgement.MISMATCH,
                repository.acknowledgeReceipt(presented),
            )
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                repository.acknowledgeConsumed(rebound),
            )
        }

    @Test
    fun `process death redelivers presented receipt after lease without consuming it`() = runTest {
        var clock = Instant.parse("2026-08-12T00:00:00Z")
        val repository = FirebaseInventoryRepository(database, remote) { clock }
        val request = acquisitionRequest("inventory-operation-presented-process-death")
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
        val receiptId = (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
        val beforeDeath =
            repository.present(
                (repository.claimReceipt(receiptId, claimant("process-a", 1, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim
            )

        assertTrue(
            repository.claimReceipt(receiptId, claimant("process-b", 1, 1))
                is InventoryReceiptClaimResult.Unavailable
        )
        assertEquals("PRESENTED", operation(request).feedbackDeliveryState)
        clock = clock.plus(Duration.ofMinutes(6))
        val restarted = FirebaseInventoryRepository(database, remote) { clock }
        val redelivered =
            (restarted.claimReceipt(receiptId, claimant("process-b", 1, 1))
                    as InventoryReceiptClaimResult.Claimed)
                .claim
        assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, redelivered.deliveryPhase)
        assertEquals(
            InventoryReceiptAcknowledgement.MISMATCH,
            restarted.acknowledgeReceipt(beforeDeath),
        )
        assertEquals("PRESENTED", operation(request).feedbackDeliveryState)
        assertEquals(
            InventoryReceiptAcknowledgement.ACKNOWLEDGED,
            restarted.acknowledgeConsumed(redelivered),
        )
    }

    @Test
    fun `durable ack pending rebinds immediately after process death without presentation`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-ack-pending-process-death")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receiptId =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val presented =
                repository.present(
                    (repository.claimReceipt(receiptId, claimant("process-a", 1, 1))
                            as InventoryReceiptClaimResult.Claimed)
                        .claim
                )
            val pending = repository.consume(presented)

            assertEquals(InventoryReceiptDeliveryPhase.ACK_PENDING, pending.deliveryPhase)
            assertEquals(presented.feedbackPresentationToken(), pending.feedbackPresentationToken())
            assertEquals("ACK_PENDING", operation(request).feedbackDeliveryState)
            assertEquals(presented.rowVersion, operation(request).feedbackRowVersion)

            val restarted = FirebaseInventoryRepository(database, remote)
            val rebound =
                (restarted.claimReceipt(receiptId, claimant("process-b", 1, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim
            assertEquals(InventoryReceiptDeliveryPhase.ACK_PENDING, rebound.deliveryPhase)
            assertTrue(rebound.rowVersion > pending.rowVersion)
            assertEquals(
                InventoryReceiptAcknowledgement.MISMATCH,
                repository.acknowledgeReceipt(pending),
            )
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                restarted.acknowledgeReceipt(rebound),
            )
        }

    @Test
    fun `concurrent controllers cannot both rebind one expired presented receipt`() = runTest {
        var clock = Instant.parse("2026-08-12T00:00:00Z")
        val repository = FirebaseInventoryRepository(database, remote) { clock }
        val request = acquisitionRequest("inventory-operation-presented-concurrent")
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
        val receiptId = (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
        repository.present(
            (repository.claimReceipt(receiptId, claimant("process-a", 1, 1))
                    as InventoryReceiptClaimResult.Claimed)
                .claim
        )
        clock = clock.plus(Duration.ofMinutes(6))

        val attempts =
            listOf(
                    async { repository.claimReceipt(receiptId, claimant("process-b", 1, 1)) },
                    async { repository.claimReceipt(receiptId, claimant("process-c", 1, 1)) },
                )
                .awaitAll()

        assertEquals(1, attempts.count { it is InventoryReceiptClaimResult.Claimed })
        assertEquals(1, attempts.count { it is InventoryReceiptClaimResult.Unavailable })
        val winner =
            (attempts.single { it is InventoryReceiptClaimResult.Claimed }
                    as InventoryReceiptClaimResult.Claimed)
                .claim
        assertEquals(InventoryReceiptDeliveryPhase.PRESENTED, winner.deliveryPhase)
        assertEquals(
            InventoryReceiptAcknowledgement.ACKNOWLEDGED,
            repository.acknowledgeConsumed(winner),
        )
    }

    @Test
    fun `same-process recreation rebinds claim and invalidates the old controller claim`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-rebind-claim")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receipt =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val oldClaim =
                (repository.claimReceipt(receipt, claimant("process-a", 1, 3))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim
            val rebound =
                (repository.claimReceipt(receipt, claimant("process-a", 2, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim

            assertTrue(rebound.rowVersion > oldClaim.rowVersion)
            assertTrue(
                repository.claimForPresentation(
                    receipt,
                    oldClaim.presentationExpectation(),
                    oldClaim.claimant,
                ) is InventoryReceiptClaimResult.Stale
            )
            assertTrue(
                repository.claimReceipt(receipt, oldClaim.claimant)
                    is InventoryReceiptClaimResult.Unavailable
            )
            assertEquals(
                InventoryReceiptAcknowledgement.MISMATCH,
                repository.acknowledgeReceipt(oldClaim),
            )
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                repository.acknowledgeConsumed(repository.present(rebound)),
            )
        }

    @Test
    fun `stale row version reports stale not missing and latest Room claim remains deliverable`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-stale-claim")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receiptId =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val first =
                (repository.claimReceipt(receiptId, claimant("same-process", 1, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim
            val latest =
                (repository.claimReceipt(receiptId, claimant("same-process", 2, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim

            assertEquals(
                InventoryReceiptClaimResult.Stale,
                repository.claimForPresentation(
                    receiptId,
                    first.presentationExpectation(),
                    first.claimant,
                ),
            )
            assertEquals("CLAIMED", operation(request).feedbackDeliveryState)
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                repository.acknowledgeConsumed(repository.present(latest)),
            )
        }

    @Test
    fun `same claimant retry keeps exact claim identity version and lease`() = runTest {
        var clock = Instant.parse("2026-08-12T00:00:00Z")
        val repository = FirebaseInventoryRepository(database, remote) { clock }
        val request = acquisitionRequest("inventory-operation-idempotent-claim")
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
        val receiptId = (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
        val claimant = claimant("same-process", 1, 1)
        val first =
            (repository.claimReceipt(receiptId, claimant) as InventoryReceiptClaimResult.Claimed)
                .claim

        clock = clock.plus(Duration.ofMinutes(4))
        val retry =
            (repository.claimForPresentation(
                    receiptId,
                    first.presentationExpectation(),
                    claimant,
                ) as InventoryReceiptClaimResult.Claimed)
                .claim

        assertEquals(first, retry)
        assertEquals(first.rowVersion, operation(request).feedbackRowVersion)
        assertEquals(first.leaseExpiresAtEpochMillis, retry.leaseExpiresAtEpochMillis)

        clock = clock.plus(Duration.ofMinutes(2))
        val replacement =
            (repository.claimReceipt(receiptId, claimant("replacement-process", 1, 1))
                    as InventoryReceiptClaimResult.Claimed)
                .claim
        assertTrue(replacement.rowVersion > first.rowVersion)
        assertEquals(first.leaseExpiresAtEpochMillis, retry.leaseExpiresAtEpochMillis)
        assertEquals(
            InventoryReceiptPresentationResult.Mismatch,
            repository.markReceiptPresented(first),
        )
        assertEquals(
            InventoryReceiptAcknowledgement.ACKNOWLEDGED,
            repository.acknowledgeConsumed(repository.present(replacement)),
        )
    }

    @Test
    fun `acknowledgement makes every earlier presentation capability terminally stale`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val request = acquisitionRequest("inventory-operation-acknowledged-capability")
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
        val receiptId = (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
        val claimant = claimant("presentation-process", 1, 1)
        val initial =
            (repository.claimForPresentation(receiptId, expected = null, claimant)
                    as InventoryReceiptClaimResult.Claimed)
                .claim
        val revalidated =
            (repository.claimForPresentation(
                    receiptId,
                    initial.presentationExpectation(),
                    claimant,
                ) as InventoryReceiptClaimResult.Claimed)
                .claim

        assertEquals(initial, revalidated)
        val presented = repository.present(revalidated)
        assertEquals(
            InventoryReceiptAcknowledgement.ACKNOWLEDGED,
            repository.acknowledgeConsumed(presented),
        )
        assertTrue(
            repository.claimForPresentation(
                receiptId,
                initial.presentationExpectation(),
                claimant,
            ) is InventoryReceiptClaimResult.Missing
        )
        assertTrue(
            repository.claimForPresentation(
                receiptId,
                revalidated.presentationExpectation(),
                claimant,
            ) is InventoryReceiptClaimResult.Missing
        )
    }

    @Test
    fun `missing receipt cannot mint a presentation claim`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val missing =
            InventoryReceiptId("${requireNotNull(remote.accountId).value}/missing-operation")

        assertEquals(
            InventoryReceiptClaimResult.Missing,
            repository.claimForPresentation(
                missing,
                expected = null,
                claimant("missing-process", 1, 1),
            ),
        )
    }

    @Test
    fun `owner switch forbids both claim and acknowledgement of the prior owner receipt`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-owner-switch-claim")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receipt =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val claim =
                (repository.claimReceipt(receipt, claimant("process-a", 1, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim

            remote.accountId = AccountId("account-b")
            assertEquals(
                InventoryReceiptClaimResult.Forbidden,
                repository.claimReceipt(receipt, claimant("process-a", 2, 1)),
            )
            assertEquals(
                InventoryReceiptAcknowledgement.FORBIDDEN,
                repository.acknowledgeReceipt(claim),
            )
            assertFalse(
                database
                    .inventoryDao()
                    .operation(request.accountId.value, request.operationId.value)
                    ?.feedbackDeliveryState == "ACKNOWLEDGED"
            )
        }

    @Test
    fun `foreign process cannot claim before lease expiry but can redeliver after expiry`() =
        runTest {
            var clock = Instant.parse("2026-08-12T00:00:00Z")
            val repository = FirebaseInventoryRepository(database, remote) { clock }
            val request = acquisitionRequest("inventory-operation-expiring-claim")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receipt =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()
            val oldClaim =
                (repository.claimReceipt(receipt, claimant("process-a", 1, 1))
                        as InventoryReceiptClaimResult.Claimed)
                    .claim

            val unavailable =
                repository.claimReceipt(receipt, claimant("process-b", 1, 1))
                    as InventoryReceiptClaimResult.Unavailable
            assertEquals(oldClaim.leaseExpiresAtEpochMillis, unavailable.retryAtEpochMillis)
            clock = clock.plus(Duration.ofMinutes(6))
            val restarted = FirebaseInventoryRepository(database, remote) { clock }
            val replacement = restarted.claimReceipt(receipt, claimant("process-b", 1, 1))
            assertTrue(replacement is InventoryReceiptClaimResult.Claimed)
            assertEquals(
                InventoryReceiptAcknowledgement.MISMATCH,
                restarted.acknowledgeReceipt(oldClaim),
            )
        }

    @Test
    fun `acknowledged receipt remains durable before retention cleanup then compacts safely`() =
        runTest {
            var clock = Instant.parse("2026-08-12T00:00:00Z")
            val repository = FirebaseInventoryRepository(database, remote) { clock }
            val request = acquisitionRequest("inventory-operation-retention")
            remote.acquireOutcome = acquiredOutcome()
            assertTrue(repository.acquire(request) is InventoryAcquireResult.Success)
            val receipt =
                (repository.load() as InventoryLoadResult.Ready).receiptCandidates.single()

            val claim =
                repository.present(
                    (repository.claimReceipt(receipt, claimant("retention-process", 1, 1))
                            as InventoryReceiptClaimResult.Claimed)
                        .claim
                )
            assertEquals(
                InventoryReceiptAcknowledgement.ACKNOWLEDGED,
                repository.acknowledgeConsumed(claim),
            )
            val acknowledged = operation(request)
            assertEquals("ACKNOWLEDGED", acknowledged.feedbackDeliveryState)
            assertEquals(clock.toEpochMilli(), acknowledged.feedbackAcknowledgedAtEpochMillis)

            clock = clock.plus(java.time.Duration.ofDays(8))
            repository.load(forceRefresh = true)
            assertEquals(
                null,
                database
                    .inventoryDao()
                    .operation(request.accountId.value, request.operationId.value),
            )
            assertEquals(
                InventoryReceiptClaimResult.Missing,
                repository.claimForPresentation(
                    receipt,
                    expected = null,
                    claimant("retention-process", 2, 1),
                ),
            )
        }

    @Test
    fun `undelivered completed receipts are ordered and isolated by owner`() = runTest {
        var clock = Instant.parse("2026-08-12T00:00:00Z")
        val repository = FirebaseInventoryRepository(database, remote) { clock }
        val acquired = acquisitionRequest("inventory-operation-order-a")
        remote.acquireOutcome = acquiredOutcome()
        assertTrue(repository.acquire(acquired) is InventoryAcquireResult.Success)
        clock = clock.plusMillis(1)
        val alreadyOwned = acquisitionRequest("inventory-operation-order-b")
        remote.acquireOutcome =
            RemoteInventoryAcquireResult.AlreadyOwned(
                alreadyOwned.accountId,
                alreadyOwned.itemId,
                alreadyOwned.expectedCatalogRevision,
                Revision(8),
                Instant.parse("2026-08-12T00:08:00Z"),
                testCatalogMediaIdentity(alreadyOwned.itemId.value, "ordered"),
            )
        assertTrue(repository.acquire(alreadyOwned) is InventoryAcquireResult.AlreadyOwned)

        val loaded = repository.load() as InventoryLoadResult.Ready
        assertEquals(
            listOf(
                InventoryReceiptId("${acquired.accountId.value}/${acquired.operationId.value}"),
                InventoryReceiptId(
                    "${alreadyOwned.accountId.value}/${alreadyOwned.operationId.value}"
                ),
            ),
            loaded.receiptCandidates,
        )
        assertTrue(operation(acquired).result?.startsWith("ACQUIRED|") == true)
        assertTrue(operation(alreadyOwned).result?.startsWith("ALREADY_OWNED|") == true)

        remote.accountId = AccountId("account-b")
        val accountB = repository.load() as InventoryLoadResult.Ready
        assertTrue(accountB.receiptCandidates.isEmpty())
    }

    @Test
    fun `replay completion first persists one receipt and held original returns the same acquired result`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-replay-first")
            val receipt = acquiredOutcome()
            remote.acquireOutcome = receipt
            val originalGate = CompletableDeferred<Unit>()
            remote.acquireGates[0] = originalGate

            val original = async { repository.acquire(request) }
            assertEquals(0, remote.acquireInvocations.receive())
            val replay = async { repository.load() }
            assertEquals(1, remote.acquireInvocations.receive())
            val replayedLoad = replay.await() as InventoryLoadResult.Ready
            assertEquals(request.itemId, replayedLoad.snapshot.owned.single().itemId)

            originalGate.complete(Unit)
            val expected = InventoryAcquireResult.Success(receipt.receiptForTest())
            assertEquals(expected, original.await())
            assertEquals("COMPLETED", operation(request).state)
            assertEquals(1, database.cacheDao().ownedItems("account-a").size)
        }

    @Test
    fun `original completion first and delayed replay return the same persisted acquired result`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-original-first")
            val receipt = acquiredOutcome()
            remote.acquireOutcome = receipt
            val originalGate = CompletableDeferred<Unit>()
            val replayGate = CompletableDeferred<Unit>()
            remote.acquireGates[0] = originalGate
            remote.acquireGates[1] = replayGate

            val original = async { repository.acquire(request) }
            assertEquals(0, remote.acquireInvocations.receive())
            val replay = async { repository.acquire(request) }
            assertEquals(1, remote.acquireInvocations.receive())
            originalGate.complete(Unit)
            val expected = InventoryAcquireResult.Success(receipt.receiptForTest())
            assertEquals(expected, original.await())

            replayGate.complete(Unit)
            assertEquals(expected, replay.await())
            assertEquals("COMPLETED", operation(request).state)
            assertEquals(0, database.cacheDao().ownedItems("account-a").size)
            repository.load()
            assertEquals(1, database.cacheDao().ownedItems("account-a").size)
        }

    @Test
    fun `N concurrent same command completions share one terminal receipt`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val request = acquisitionRequest("inventory-operation-concurrent")
        val receipt = acquiredOutcome()
        remote.acquireOutcome = receipt
        val gate = CompletableDeferred<Unit>()
        repeat(CONCURRENT_COMPLETIONS) { remote.acquireGates[it] = gate }

        val completions = List(CONCURRENT_COMPLETIONS) { async { repository.acquire(request) } }
        repeat(CONCURRENT_COMPLETIONS) { assertEquals(it, remote.acquireInvocations.receive()) }
        gate.complete(Unit)

        val expected = InventoryAcquireResult.Success(receipt.receiptForTest())
        assertEquals(List(CONCURRENT_COMPLETIONS) { expected }, completions.awaitAll())
        assertEquals("COMPLETED", operation(request).state)
        assertEquals(0, database.cacheDao().ownedItems("account-a").size)
        repository.load()
        assertEquals(1, database.cacheDao().ownedItems("account-a").size)
    }

    @Test
    fun `already owned replay completion first and held original expose one exact receipt`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-already-owned-replay-first")
            val receipt =
                RemoteInventoryAcquireResult.AlreadyOwned(
                    request.accountId,
                    request.itemId,
                    request.expectedCatalogRevision,
                    Revision(3),
                    Instant.parse("2026-08-12T00:03:00Z"),
                    testCatalogMediaIdentity(request.itemId.value, "replay-first"),
                )
            remote.acquireOutcome = receipt
            val originalGate = CompletableDeferred<Unit>()
            remote.acquireGates[0] = originalGate

            val original = async { repository.acquire(request) }
            assertEquals(0, remote.acquireInvocations.receive())
            val replay = async { repository.load() }
            assertEquals(1, remote.acquireInvocations.receive())
            val replayed = replay.await() as InventoryLoadResult.Ready
            val expected = InventoryAcquireResult.AlreadyOwned(receipt.receiptForTest())
            assertEquals(
                listOf(
                    InventoryReceiptId("${request.accountId.value}/${request.operationId.value}")
                ),
                replayed.receiptCandidates,
            )

            originalGate.complete(Unit)
            assertEquals(expected, original.await())
            assertEquals("COMPLETED", operation(request).state)
            assertTrue(operation(request).result?.startsWith("ALREADY_OWNED|") == true)
        }

    @Test
    fun `already owned terminal receipt replays exactly and altered payload remains mismatch`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-already-owned")
            val receipt =
                RemoteInventoryAcquireResult.AlreadyOwned(
                    request.accountId,
                    request.itemId,
                    request.expectedCatalogRevision,
                    Revision(3),
                    Instant.parse("2026-08-12T00:03:00Z"),
                    testCatalogMediaIdentity(request.itemId.value, "terminal-replay"),
                )
            remote.acquireOutcome = receipt
            val expected = InventoryAcquireResult.AlreadyOwned(receipt.receiptForTest())

            assertEquals(expected, repository.acquire(request))
            assertEquals(expected, repository.acquire(request))
            assertEquals(1, remote.acquireRequests.size)
            assertEquals(
                InventoryAcquireResult.Failure(
                    InventoryFailure.IDEMPOTENCY_MISMATCH,
                    request.operationId,
                ),
                repository.acquire(request.copy(expectedCatalogRevision = Revision(9))),
            )
            assertEquals(1, remote.acquireRequests.size)
        }

    @Test
    fun `replaced operation row cannot be consumed by a stale completion`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val request = acquisitionRequest("inventory-operation-replaced")
        remote.acquireOutcome = acquiredOutcome()
        val gate = CompletableDeferred<Unit>()
        remote.acquireGates[0] = gate

        val stale = async { repository.acquire(request) }
        assertEquals(0, remote.acquireInvocations.receive())
        val pending = operation(request)
        assertEquals(
            1,
            database
                .inventoryDao()
                .deleteOperation(
                    pending.accountId,
                    pending.operationId,
                    pending.itemId,
                    pending.expectedCatalogRevision,
                    pending.requestHash,
                ),
        )
        database
            .inventoryDao()
            .insertOperation(
                pending.copy(itemId = "replacement-item", requestHash = "replacement-hash")
            )
        gate.complete(Unit)

        assertEquals(
            InventoryAcquireResult.Failure(
                InventoryFailure.IDEMPOTENCY_MISMATCH,
                request.operationId,
            ),
            stale.await(),
        )
        assertTrue(database.cacheDao().ownedItems("account-a").isEmpty())
        assertEquals("replacement-item", operation(request).itemId)
    }

    @Test
    fun `database write rejection is a true database failure and never a synthetic receipt`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val request = acquisitionRequest("inventory-operation-database-failure")
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_inventory_operation BEFORE INSERT ON inventory_acquisition_operations BEGIN SELECT RAISE(ABORT, 'forced database failure'); END"
            )

            assertEquals(
                InventoryAcquireResult.Failure(InventoryFailure.DATABASE, request.operationId),
                repository.acquire(request),
            )
            assertTrue(remote.acquireRequests.isEmpty())
        }

    @Test
    fun `late A acquisition after auth switches B cannot mutate B or consume A replay`() = runTest {
        val repository = FirebaseInventoryRepository(database, remote)
        val operationId = OperationId("inventory-operation-owner-race")
        val request =
            InventoryAcquireRequest(
                AccountId("account-a"),
                ItemId("free-item"),
                Revision(2),
                operationId,
            )
        remote.acquireOutcome =
            RemoteInventoryAcquireResult.Acquired(
                AccountId("account-a"),
                ItemId("free-item"),
                Revision(2),
                Revision(1),
                Instant.parse("2026-08-12T00:00:00Z"),
                testCatalogMediaIdentity("free-item", "owner-race"),
            )
        val gate = CompletableDeferred<Unit>()
        remote.acquireGate = gate

        val lateA = async { repository.acquire(request) }
        remote.acquireStarted.await()
        remote.accountId = AccountId("account-b")
        gate.complete(Unit)
        assertEquals(InventoryAcquireResult.Forbidden, lateA.await())
        assertTrue(database.cacheDao().ownedItems("account-a").isEmpty())
        assertTrue(database.cacheDao().ownedItems("account-b").isEmpty())
        assertEquals(1, database.inventoryDao().pendingOperations("account-a").size)

        remote.accountId = AccountId("account-a")
        remote.acquireGate = null
        val replayed = repository.load() as InventoryLoadResult.Ready
        assertEquals(ItemId("free-item"), replayed.snapshot.owned.single().itemId)
        assertEquals(0, database.inventoryDao().pendingOperations("account-a").size)
        assertTrue(database.cacheDao().ownedItems("account-b").isEmpty())
    }

    @Test
    fun `condition already owned catalog conflict and altered local replay remain typed`() =
        runTest {
            val repository = FirebaseInventoryRepository(database, remote)
            val operation = OperationId("inventory-operation-0002")
            val request =
                InventoryAcquireRequest(
                    AccountId("account-a"),
                    ItemId("plant-item"),
                    Revision(3),
                    operation,
                )
            remote.acquireOutcome =
                RemoteInventoryAcquireResult.ConditionNotMet(
                    AccountId("account-a"),
                    ItemId("plant-item"),
                    Revision(3),
                    AcquisitionCondition.REGISTERED_PLANT,
                )
            assertEquals(
                InventoryAcquireResult.ConditionNotMet(
                    ItemId("plant-item"),
                    Revision(3),
                    AcquisitionCondition.REGISTERED_PLANT,
                ),
                repository.acquire(request),
            )

            remote.failure = InventoryFailure.CATALOG_CHANGED
            assertEquals(
                InventoryAcquireResult.Failure(
                    InventoryFailure.CATALOG_CHANGED,
                    OperationId("inventory-operation-0003"),
                ),
                repository.acquire(
                    request.copy(operationId = OperationId("inventory-operation-0003"))
                ),
            )
        }

    private class BootstrapRemote(
        var accountId: AccountId,
        private val authoritative: InventorySnapshot,
    ) : InventoryRemoteDataSource {
        var offline = false
        private var loads = 0

        override fun activeAccount(): AccountId = accountId

        override suspend fun load(accountId: AccountId): InventorySnapshot {
            check(accountId == this.accountId)
            if (offline || loads++ == 0) throw IOException("offline during bootstrap")
            return authoritative
        }

        override suspend fun acquire(
            request: RemoteInventoryAcquireRequest
        ): RemoteInventoryAcquireResult = error("not used")
    }

    private class FakeInventoryRemote : InventoryRemoteDataSource {
        var accountId = AccountId("account-a")
        var loadFailure: Exception? = null
        val loadGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val nonCancellableLoads = mutableSetOf<Int>()
        val loadSnapshots = mutableMapOf<Int, InventorySnapshot>()
        val loadInvocations = Channel<Int>(Channel.UNLIMITED)
        private var loadInvocation = 0
        var failure: InventoryFailure? = null
        var loseFirstResponse = false
        var acquireGate: CompletableDeferred<Unit>? = null
        val acquireGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val acquireInvocations = Channel<Int>(Channel.UNLIMITED)
        val acquireStarted = CompletableDeferred<Unit>()
        var loadedSnapshot: InventorySnapshot? = null
        var acquireOutcome: RemoteInventoryAcquireResult =
            RemoteInventoryAcquireResult.AlreadyOwned(
                AccountId("account-a"),
                ItemId("free-item"),
                Revision(2),
                Revision(1),
                Instant.parse("2026-08-12T00:00:00Z"),
                testCatalogMediaIdentity("free-item", "fake-default"),
            )
        val acquireRequests = mutableListOf<RemoteInventoryAcquireRequest>()
        private var lost = false

        override fun activeAccount(): AccountId = accountId

        override suspend fun load(accountId: AccountId): InventorySnapshot {
            loadFailure?.let { throw it }
            val invocation = loadInvocation++
            loadInvocations.send(invocation)
            val response = loadSnapshots[invocation] ?: loadedSnapshot ?: snapshot(accountId)
            loadGates[invocation]?.let { gate ->
                if (invocation in nonCancellableLoads) withContext(NonCancellable) { gate.await() }
                else gate.await()
            }
            return response
        }

        override suspend fun acquire(
            request: RemoteInventoryAcquireRequest
        ): RemoteInventoryAcquireResult {
            acquireStarted.complete(Unit)
            val invocation = acquireRequests.size
            acquireRequests += request
            acquireInvocations.send(invocation)
            (acquireGates[invocation] ?: acquireGate)?.let { gate ->
                withContext(NonCancellable) { gate.await() }
            }
            failure?.let { throw InventoryRemoteException(it) }
            if (loseFirstResponse && !lost) {
                lost = true
                throw IOException("response lost after commit")
            }
            return acquireOutcome
        }

        private fun snapshot(accountId: AccountId) =
            InventorySnapshot(
                accountId,
                listOf(
                    item("free-item", Revision(2), null),
                    item("plant-item", Revision(3), AcquisitionCondition.REGISTERED_PLANT),
                ),
                if (acquireRequests.isEmpty()) emptyList()
                else
                    listOf(
                        OwnedInventoryItem(
                            ItemId("free-item"),
                            Instant.parse("2026-08-12T00:00:00Z"),
                            false,
                            Revision(1),
                        )
                    ),
                0,
                Instant.parse("2026-08-12T01:00:00Z"),
                generation = if (acquireRequests.isEmpty()) 1 else 2,
            )

        private fun item(
            id: String,
            revision: Revision,
            condition: AcquisitionCondition?,
        ) =
            InventoryItem(
                ItemId(id),
                id,
                "$id 설명",
                ItemCategory.DECORATION,
                "catalog-assets/$id/preview.webp",
                condition,
                revision,
                Instant.EPOCH,
            )
    }

    private fun snapshot(accountId: AccountId, itemId: String) =
        InventorySnapshot(
            accountId,
            listOf(inventoryItem(itemId)),
            emptyList(),
            0,
            Instant.parse("2026-08-12T01:00:00Z"),
            generation = 2,
        )

    private fun inventoryItem(id: String) =
        InventoryItem(
            ItemId(id),
            id,
            "$id description",
            ItemCategory.DECORATION,
            "catalog-assets/$id/preview.webp",
            null,
            Revision(1),
            Instant.EPOCH,
        )

    private fun cachedItem(account: AccountId, id: String): CachedShopItemEntity {
        val digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return CachedShopItemEntity(
            accountId = account.value,
            itemId = id,
            name = id,
            description = "$id description",
            category = ItemCategory.DECORATION.name,
            assetPath = "catalog-assets/$id/$digest.webp",
            assetSha256 = digest,
            assetByteSize = 1,
            assetMimeType = "image/webp",
            assetWidth = 1,
            assetHeight = 1,
            assetMediaRevision = 1,
            acquisitionCondition = null,
            revision = 1,
            updatedAtEpochMillis = 0,
        )
    }

    private fun authoritativeOwnedSnapshot(
        base: InventorySnapshot,
        itemIds: List<String>,
        generation: Long,
    ): InventorySnapshot {
        val owned = itemIds.map { itemId ->
            OwnedInventoryItem(
                ItemId(itemId),
                Instant.parse("2026-08-12T00:00:00Z"),
                false,
                Revision(1),
            )
        }
        return InventorySnapshot(
            accountId = base.accountId,
            catalog = base.catalog,
            owned = owned,
            registeredPlantCount = base.registeredPlantCount,
            loadedAt = base.loadedAt.plusSeconds(generation),
            partial = false,
            generation = generation,
        )
    }

    private fun acquisitionRequest(operationId: String) =
        InventoryAcquireRequest(
            AccountId("account-a"),
            ItemId("free-item"),
            Revision(2),
            OperationId(operationId),
        )

    private fun acquiredOutcome() =
        RemoteInventoryAcquireResult.Acquired(
            AccountId("account-a"),
            ItemId("free-item"),
            Revision(2),
            Revision(1),
            Instant.parse("2026-08-12T00:00:00Z"),
            testCatalogMediaIdentity("free-item", "acquired-outcome"),
        )

    private suspend fun operation(request: InventoryAcquireRequest) =
        requireNotNull(
            database.inventoryDao().operation(request.accountId.value, request.operationId.value)
        )

    private fun RemoteInventoryAcquireResult.Acquired.receiptForTest() =
        InventoryOwnershipReceipt(
            accountId,
            itemId,
            catalogRevision,
            ownershipRevision,
            acquiredAt,
            mediaIdentity,
        )

    private fun RemoteInventoryAcquireResult.AlreadyOwned.receiptForTest() =
        InventoryOwnershipReceipt(
            accountId,
            itemId,
            catalogRevision,
            ownershipRevision,
            acquiredAt,
            mediaIdentity,
        )

    private fun claimant(
        process: String,
        controller: Long,
        generation: Long,
    ) = InventoryReceiptClaimant(process, controller, generation)

    private suspend fun FirebaseInventoryRepository.claimReceipt(
        receiptId: InventoryReceiptId,
        claimant: InventoryReceiptClaimant,
    ): InventoryReceiptClaimResult = claimForPresentation(receiptId, expected = null, claimant)

    private suspend fun FirebaseInventoryRepository.present(
        claim: InventoryReceiptClaim
    ): InventoryReceiptClaim =
        (markReceiptPresented(claim) as InventoryReceiptPresentationResult.Presented).claim

    private suspend fun FirebaseInventoryRepository.consume(
        claim: InventoryReceiptClaim
    ): InventoryReceiptClaim =
        (markReceiptConsumed(claim) as InventoryReceiptConsumptionResult.PendingAcknowledgement)
            .claim

    private suspend fun FirebaseInventoryRepository.acknowledgeConsumed(
        claim: InventoryReceiptClaim
    ): InventoryReceiptAcknowledgement = acknowledgeReceipt(consume(claim))

    private companion object {
        const val CONCURRENT_COMPLETIONS = 16
    }
}
