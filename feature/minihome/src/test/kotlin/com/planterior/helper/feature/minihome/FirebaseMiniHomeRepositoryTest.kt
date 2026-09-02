package com.planterior.helper.feature.minihome

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeOwnedCatalogSnapshot
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.data.INVENTORY_CONTRACT_VERSION
import com.planterior.helper.core.data.InconsistentMiniHomeLayoutException
import com.planterior.helper.core.data.authoritativeInventorySnapshotHash
import com.planterior.helper.core.data.cacheWrite
import com.planterior.helper.core.data.verifiedAuthoritativeInventoryOrNull
import com.planterior.helper.core.database.AuthoritativeMiniHomeCacheWrite
import com.planterior.helper.core.database.CachedInventoryState
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedMiniHomePlacementEntity
import com.planterior.helper.core.database.CachedOwnedItemEntity
import com.planterior.helper.core.database.CachedShopItemEntity
import com.planterior.helper.core.database.InventorySnapshotWatermarkEntity
import com.planterior.helper.core.database.MiniHomeCacheWatermarkEntity
import com.planterior.helper.core.database.MiniHomeCacheWatermarkKind
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.database.RoomTransactionOwner
import com.planterior.helper.core.database.RoomTransactionOwnerDiagnostics
import com.planterior.helper.core.database.RoomTransactionOwnerObservation
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseMiniHomeRepositoryTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PlanteriorDatabase::class.java,
                )
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `every callable status maps exhaustively and only transport statuses are transient`() {
        val expected =
            mapOf(
                FirebaseFunctionsException.Code.OK to MiniHomeSaveFailure.MALFORMED_RESPONSE,
                FirebaseFunctionsException.Code.CANCELLED to MiniHomeSaveFailure.NETWORK,
                FirebaseFunctionsException.Code.UNKNOWN to MiniHomeSaveFailure.MALFORMED_RESPONSE,
                FirebaseFunctionsException.Code.INVALID_ARGUMENT to
                    MiniHomeSaveFailure.INVALID_REQUEST,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED to MiniHomeSaveFailure.NETWORK,
                FirebaseFunctionsException.Code.NOT_FOUND to MiniHomeSaveFailure.INVALID_REQUEST,
                FirebaseFunctionsException.Code.ALREADY_EXISTS to
                    MiniHomeSaveFailure.INVALID_REQUEST,
                FirebaseFunctionsException.Code.PERMISSION_DENIED to
                    MiniHomeSaveFailure.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED to MiniHomeSaveFailure.NETWORK,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION to
                    MiniHomeSaveFailure.INVALID_REQUEST,
                FirebaseFunctionsException.Code.ABORTED to MiniHomeSaveFailure.REVISION_CONFLICT,
                FirebaseFunctionsException.Code.OUT_OF_RANGE to MiniHomeSaveFailure.INVALID_REQUEST,
                FirebaseFunctionsException.Code.UNIMPLEMENTED to
                    MiniHomeSaveFailure.INVALID_REQUEST,
                FirebaseFunctionsException.Code.INTERNAL to MiniHomeSaveFailure.MALFORMED_RESPONSE,
                FirebaseFunctionsException.Code.UNAVAILABLE to MiniHomeSaveFailure.NETWORK,
                FirebaseFunctionsException.Code.DATA_LOSS to MiniHomeSaveFailure.MALFORMED_RESPONSE,
                FirebaseFunctionsException.Code.UNAUTHENTICATED to
                    MiniHomeSaveFailure.PERMISSION_DENIED,
            )

        assertEquals(FirebaseFunctionsException.Code.entries.toSet(), expected.keys)
        expected.forEach { (code, failure) ->
            assertEquals(code.name, failure, mapMiniHomeCallableFailure(code, null))
        }
    }

    @Test
    fun `every typed callable reason maps exactly regardless of generic status`() {
        val expected =
            mapOf(
                "OUTBOX_MISMATCH" to MiniHomeSaveFailure.OUTBOX_MISMATCH,
                "PAYLOAD_MISMATCH" to MiniHomeSaveFailure.PAYLOAD_MISMATCH,
                "UNAVAILABLE_ENTITY" to MiniHomeSaveFailure.UNAVAILABLE_ENTITY,
                "REVISION_CONFLICT" to MiniHomeSaveFailure.REVISION_CONFLICT,
                "INVALID_REQUEST" to MiniHomeSaveFailure.INVALID_REQUEST,
                "PERMISSION_DENIED" to MiniHomeSaveFailure.PERMISSION_DENIED,
                "MALFORMED_RESPONSE" to MiniHomeSaveFailure.MALFORMED_RESPONSE,
            )

        expected.forEach { (reason, failure) ->
            assertEquals(
                reason,
                failure,
                mapMiniHomeCallableFailure(
                    FirebaseFunctionsException.Code.UNKNOWN,
                    mapOf("reason" to reason),
                ),
            )
        }
    }

    @Test
    fun `callable details map exact mismatch and unavailable reasons`() {
        assertEquals(
            MiniHomeSaveFailure.OUTBOX_MISMATCH,
            mapMiniHomeCallableFailure(
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                mapOf("reason" to "OUTBOX_MISMATCH"),
            ),
        )
        assertEquals(
            MiniHomeSaveFailure.PAYLOAD_MISMATCH,
            mapMiniHomeCallableFailure(
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                mapOf("reason" to "PAYLOAD_MISMATCH"),
            ),
        )
        assertEquals(
            MiniHomeSaveFailure.UNAVAILABLE_ENTITY,
            mapMiniHomeCallableFailure(
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                mapOf("reason" to "UNAVAILABLE_ENTITY"),
            ),
        )
        assertEquals(
            MiniHomeSaveFailure.INVALID_REQUEST,
            mapMiniHomeCallableFailure(
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                mapOf("reason" to "INVALID_REQUEST", "field" to "name"),
            ),
        )
        assertEquals(
            MiniHomeSaveFailure.MALFORMED_RESPONSE,
            mapMiniHomeCallableFailure(FirebaseFunctionsException.Code.DATA_LOSS, null),
        )
        assertEquals(
            MiniHomeSaveFailure.PERMISSION_DENIED,
            mapMiniHomeCallableFailure(FirebaseFunctionsException.Code.PERMISSION_DENIED, null),
        )
        assertEquals(
            "field=name;reason=INVALID_REQUEST",
            miniHomeCallableFailureDetails(mapOf("reason" to "INVALID_REQUEST", "field" to "name")),
        )
        assertEquals(
            MiniHomeSaveFailure.NETWORK,
            mapMiniHomeCallableFailure(FirebaseFunctionsException.Code.UNAVAILABLE, null),
        )
    }

    @Test
    fun `client contract rejects surrounding whitespace before outbox and remote boundary`() =
        runTest {
            val remote = FakeRemote(layout(3))
            val repository = FirebaseMiniHomeRepository(database, remote)
            val invalid = request("operation-client-invalid", layout(3).copy(name = " invalid "))

            val result = repository.save(invalid)

            assertTrue(result is MiniHomeSaveResult.RequiresCorrection)
            assertTrue(remote.savedRequests.isEmpty())
            assertNull(database.syncDao().operation("account-a", "operation-client-invalid"))
        }

    @Test
    fun `server validation retires invalid operation blocks unchanged resend and admits corrected new operation`() =
        runTest {
            val remote =
                FakeRemote(
                    layout(3),
                    RemoteMiniHomeSaveResult.Failed(
                        MiniHomeSaveFailure.INVALID_REQUEST,
                        "field=name;reason=INVALID_REQUEST",
                    ),
                )
            val repository = FirebaseMiniHomeRepository(database, remote)
            val invalid =
                request("operation-invalid-name", layout(3).copy(name = "server-rejected"))

            assertEquals(
                MiniHomeSaveResult.RequiresCorrection(
                    MiniHomeSaveFailure.INVALID_REQUEST,
                    "field=name;reason=INVALID_REQUEST",
                ),
                repository.save(invalid),
            )
            assertEquals(
                MiniHomeSaveResult.RequiresCorrection(
                    MiniHomeSaveFailure.INVALID_REQUEST,
                    "field=name;reason=INVALID_REQUEST",
                ),
                repository.save(invalid),
            )
            assertEquals(1, remote.savedRequests.size)
            val retired =
                requireNotNull(database.syncDao().operation("account-a", "operation-invalid-name"))
            assertEquals("RECONCILIATION_REQUIRED", retired.state)
            assertEquals("INVALID_REQUEST", retired.lastErrorCode)

            remote.saveResult = RemoteMiniHomeSaveResult.Applied(Revision(4))
            val corrected =
                request("operation-corrected-name", layout(3).copy(name = "수정한 이름"))
                    .copy(
                        lineageId = invalid.lineageId,
                        supersedesOperationId = invalid.operationId,
                    )
            assertTrue(repository.save(corrected) is MiniHomeSaveResult.Saved)
            assertEquals(2, remote.savedRequests.size)
            assertEquals("operation-corrected-name", remote.savedRequests.last().operationId.value)
            assertNull(database.syncDao().operation("account-a", "operation-invalid-name"))
        }

    @Test
    fun `discarding corrected unsaved operation removes prior invalid tombstone without unrelated operations`() =
        runTest {
            val remote =
                FakeRemote(
                    layout(3),
                    RemoteMiniHomeSaveResult.Failed(
                        MiniHomeSaveFailure.INVALID_REQUEST,
                        "field=name;reason=INVALID_REQUEST",
                    ),
                )
            val repository = FirebaseMiniHomeRepository(database, remote)
            val rejected = request("lineage-invalid-operation", layout(3).copy(name = "거절된 편집"))
            repository.save(rejected)
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "unrelated-operation",
                        "account-a",
                        "personalPlants",
                        "plant-a",
                        "UPDATE",
                        1,
                        "unrelated-draft",
                        2,
                    )
                )

            assertEquals(
                MiniHomeDiscardResult.Consumed,
                abandonCurrent(repository, "account-a", rejected.operationId.value),
            )

            assertNull(database.syncDao().operation("account-a", "lineage-invalid-operation"))
            assertNotNull(database.syncDao().operation("account-a", "unrelated-operation"))
            val restarted =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
            assertNull(restarted.pending)
            assertEquals("저장된 방", restarted.committed.name)
        }

    @Test
    fun `multi correction chain survives restart at its head and discard removes the lineage only`() =
        runTest {
            val remote =
                FakeRemote(
                    layout(3),
                    RemoteMiniHomeSaveResult.Failed(
                        MiniHomeSaveFailure.INVALID_REQUEST,
                        "field=name",
                    ),
                )
            val repository = FirebaseMiniHomeRepository(database, remote)
            val root = request("lineage-chain-root", layout(3).copy(name = "첫 편집"))
            repository.save(root)
            val second =
                request("lineage-chain-second", layout(3).copy(name = "둘째 편집"))
                    .copy(lineageId = root.lineageId, supersedesOperationId = root.operationId)
            repository.save(second)
            val third =
                request("lineage-chain-third", layout(3).copy(name = "셋째 편집"))
                    .copy(lineageId = root.lineageId, supersedesOperationId = second.operationId)
            repository.save(third)
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "unrelated-chain-operation",
                        "account-a",
                        "personalPlants",
                        "plant-a",
                        "UPDATE",
                        1,
                        "unrelated",
                        4,
                    )
                )
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "lineage-chain-root",
                        "account-b",
                        "miniHomeLayouts",
                        "home-b",
                        "REPLACE",
                        1,
                        "other-owner",
                        5,
                        lineageId = "lineage-chain-root",
                    )
                )

            val restored =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
            assertEquals(third.operationId, restored.pending?.operationId)
            assertEquals(root.lineageId, restored.pending?.lineageId)
            assertEquals(second.operationId, restored.pending?.supersedesOperationId)

            val chainHandle = requireNotNull(restored.pending?.discardHandle)
            remote.account = AccountId("account-b")
            assertEquals(MiniHomeDiscardResult.OwnerMismatch, repository.abandon(chainHandle))
            listOf(root, second, third).forEach {
                assertNotNull(database.syncDao().operation("account-a", it.operationId.value))
            }

            remote.account = AccountId("account-a")
            assertEquals(MiniHomeDiscardResult.Consumed, repository.abandon(chainHandle))
            listOf(root, second, third).forEach {
                assertNull(database.syncDao().operation("account-a", it.operationId.value))
            }
            assertNotNull(database.syncDao().operation("account-a", "unrelated-chain-operation"))
            assertNotNull(database.syncDao().operation("account-b", "lineage-chain-root"))
            val afterDiscard =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
            assertNull(afterDiscard.pending)
            assertEquals("저장된 방", afterDiscard.committed.name)
        }

    @Test
    fun `response loss successor discard removes uncertain and invalid ancestors`() = runTest {
        val remote =
            FakeRemote(
                layout(3),
                RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.INVALID_REQUEST),
            )
        val repository = FirebaseMiniHomeRepository(database, remote)
        val root = request("lineage-loss-root", layout(3).copy(name = "거절된 편집"))
        repository.save(root)
        remote.saveResult = RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
        val successor =
            request("lineage-loss-successor", layout(3).copy(name = "수정한 편집"))
                .copy(lineageId = root.lineageId, supersedesOperationId = root.operationId)
        repository.save(successor)

        assertEquals(
            MiniHomeDiscardResult.Consumed,
            abandonCurrent(repository, "account-a", successor.operationId.value),
        )

        assertNull(database.syncDao().operation("account-a", root.operationId.value))
        assertNull(database.syncDao().operation("account-a", successor.operationId.value))
        val restored =
            FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
        assertNull(restored.pending)
    }

    @Test
    fun `fresh layout is cached and restores exactly when offline`() = runTest {
        val remote = FakeRemote(layout(3))
        val repository = FirebaseMiniHomeRepository(database, remote)
        val fresh = repository.load() as MiniHomeLoadResult.Ready
        remote.failLoad = true

        val stale = repository.load() as MiniHomeLoadResult.Ready

        assertEquals(fresh.committed, stale.committed)
        assertTrue(stale.stale)
        assertEquals(GridPosition(2, 2), stale.committed.placements.single().position)
    }

    @Test
    fun `held cache transaction delays publication until coherent Room read can proceed`() =
        runTest {
            val remoteEntered = CompletableDeferred<Unit>()
            val releaseRemote = CompletableDeferred<Unit>()
            val delegate =
                FakeRemote(layout(7)).apply {
                    cacheGeneration = 7
                    onLoad = {
                        remoteEntered.complete(Unit)
                        releaseRemote.await()
                    }
                }
            val remoteReturned = CompletableDeferred<AccountId>()
            val remote =
                object : MiniHomeRemoteDataSource {
                    override fun activeAccount(): AccountId = delegate.activeAccount()

                    override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
                        val snapshot = delegate.load(accountId)
                        remoteReturned.complete(snapshot.accountId)
                        return snapshot
                    }

                    override suspend fun save(
                        request: MiniHomeSaveRequest
                    ): RemoteMiniHomeSaveResult = delegate.save(request)
                }
            val publicationEntered = CompletableDeferred<AccountId>()
            val publicationReadIdentity = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val publicationReturned =
                CompletableDeferred<Pair<AccountId, MiniHomePublicationReadIdentity>>()
            val cacheEntered = CompletableDeferred<AccountId>()
            val cacheReturned = CompletableDeferred<Pair<AccountId, Boolean>>()
            val cacheTransactionEntered = CompletableDeferred<Unit>()
            val cacheTransactionObservations =
                ConcurrentLinkedQueue<MiniHomeCacheTransactionDiagnosticObservation>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    remote,
                    beforeCacheApply = { cacheEntered.complete(it) },
                    afterCacheApply = { accountId, current ->
                        cacheReturned.complete(accountId to current)
                    },
                    beforePublicationRead = { accountId, readIdentity ->
                        publicationEntered.complete(accountId)
                        publicationReadIdentity.complete(readIdentity)
                    },
                    afterPublicationRead = { accountId, readIdentity ->
                        publicationReturned.complete(accountId to readIdentity)
                    },
                    onCacheTransactionDiagnostic = { observation ->
                        cacheTransactionObservations.add(observation)
                        if (
                            observation.stage ==
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED
                        ) {
                            cacheTransactionEntered.complete(Unit)
                        }
                    },
                )
            val transactionHeld = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()

            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { loadDispatcher ->
                val loading = async(loadDispatcher) { repository.load() }
                remoteEntered.await()
                val transaction =
                    async(Dispatchers.IO) {
                        database.withTransaction {
                            database.cacheDao().clearMiniHome("publication-transaction-gate")
                            transactionHeld.complete(Unit)
                            releaseTransaction.await()
                        }
                    }
                transactionHeld.await()
                releaseRemote.complete(Unit)
                assertEquals(AccountId("account-a"), remoteReturned.await())
                val loadDispatcherDrained = CompletableDeferred<Unit>()
                launch(loadDispatcher) { loadDispatcherDrained.complete(Unit) }
                loadDispatcherDrained.await()

                assertEquals(AccountId("account-a"), cacheEntered.await())
                cacheTransactionEntered.await()
                assertEquals(
                    listOf(MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED),
                    cacheTransactionObservations.map { it.stage },
                )
                assertFalse(transaction.isCompleted)
                assertFalse(loading.isCompleted)
                assertFalse(cacheReturned.isCompleted)
                assertFalse(publicationEntered.isCompleted)
                assertFalse(publicationReturned.isCompleted)

                releaseTransaction.complete(Unit)
                transaction.await()
                assertEquals(AccountId("account-a") to true, cacheReturned.await())
                assertEquals(AccountId("account-a"), publicationEntered.await())
                assertEquals(
                    AccountId("account-a") to publicationReadIdentity.await(),
                    publicationReturned.await(),
                )
                val loaded = loading.await() as MiniHomeLoadResult.Ready
                assertEquals(AccountId("account-a"), loaded.accountId)
                assertEquals(Revision(7), loaded.committed.revision)
                assertFalse(loaded.stale)
                assertEquals(
                    listOf(
                        MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED,
                        MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_ENTERED,
                        MiniHomeCacheTransactionDiagnosticStage.LAYOUT_APPLY,
                        MiniHomeCacheTransactionDiagnosticStage.INVENTORY_APPLY,
                        MiniHomeCacheTransactionDiagnosticStage.CURRENT_SNAPSHOT,
                        MiniHomeCacheTransactionDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                        MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_RETURNED,
                        MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_SCOPE_RETURNED,
                        MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_RETURNED,
                    ),
                    cacheTransactionObservations.map { it.stage },
                )
                assertEquals(
                    MiniHomeCacheTransactionResult.CURRENT,
                    cacheTransactionObservations.last().result,
                )
            }
        }

    @Test
    fun `pending query entry is observable before second publication read and load terminal`() =
        runTest {
            val pendingReadEntered = CompletableDeferred<MiniHomePendingReadIdentity>()
            val releasePendingRead = CompletableDeferred<Unit>()
            val pendingReadObservations =
                mutableListOf<Pair<MiniHomePendingReadIdentity, MiniHomePendingReadOutcome>>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    beforePendingRead = { _, identity ->
                        if (identity.queryOrdinal == 2L) {
                            pendingReadEntered.complete(identity)
                            releasePendingRead.await()
                        }
                    },
                    afterPendingRead = { _, identity, outcome ->
                        pendingReadObservations += identity to outcome
                    },
                )

            val loading = async { repository.load() }
            val pendingIdentity = pendingReadEntered.await()

            assertEquals(2L, pendingIdentity.queryOrdinal)
            assertTrue(
                pendingReadObservations.none { (identity, _) -> identity == pendingIdentity }
            )
            assertFalse(loading.isCompleted)

            releasePendingRead.complete(Unit)
            val loaded = loading.await() as MiniHomeLoadResult.Ready

            assertEquals(2, pendingReadObservations.size)
            assertEquals(
                pendingIdentity to MiniHomePendingReadOutcome.Returned,
                pendingReadObservations.last(),
            )
            assertEquals(Revision(7), loaded.committed.revision)
            assertFalse(loaded.stale)
        }

    @Test
    fun `pending query cancellation reports the exact cancellation after a gated query`() =
        runTest {
            val pendingReadEntered = CompletableDeferred<MiniHomePendingReadIdentity>()
            val releasePendingRead = CompletableDeferred<Unit>()
            val pendingReadObservations =
                mutableListOf<Pair<MiniHomePendingReadIdentity, MiniHomePendingReadOutcome>>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    beforePendingRead = { _, identity ->
                        if (identity.queryOrdinal == 2L) {
                            pendingReadEntered.complete(identity)
                            releasePendingRead.await()
                        }
                    },
                    afterPendingRead = { _, identity, outcome ->
                        pendingReadObservations += identity to outcome
                    },
                )

            val loading = async { repository.load() }
            val pendingIdentity = pendingReadEntered.await()
            val expected = CancellationException("cancel pending query")
            loading.cancel(expected)
            runCurrent()
            releasePendingRead.complete(Unit)
            loading.join()

            val outcome = pendingReadObservations.last { it.first == pendingIdentity }.second
            assertTrue(outcome is MiniHomePendingReadOutcome.Cancelled)
            assertEquals(
                expected.message,
                (outcome as MiniHomePendingReadOutcome.Cancelled).failure.message,
            )
        }

    @Test
    fun `pending query diagnostic observer faults do not alter load behavior`() = runTest {
        val pendingReadEntered = CompletableDeferred<MiniHomePendingReadIdentity>()
        var afterPendingReadCalls = 0
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                beforePendingRead = { _, identity ->
                    if (identity.queryOrdinal == 2L) {
                        pendingReadEntered.complete(identity)
                        throw IllegalStateException("observer before failure")
                    }
                },
                afterPendingRead = { _, identity, _ ->
                    if (identity.queryOrdinal == 2L) {
                        afterPendingReadCalls += 1
                        throw AssertionError("observer after failure")
                    }
                },
            )

        val loaded = async { repository.load() }.await()

        assertTrue(loaded is MiniHomeLoadResult.Ready)
        assertEquals(1, afterPendingReadCalls)
        assertEquals(2L, pendingReadEntered.await().queryOrdinal)
    }

    @Test
    fun `second publication read suspended by Room cancellation does not report a return`() =
        runTest {
            val secondReadEntered = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val releaseSecondReadCallback = CompletableDeferred<Unit>()
            val firstReadReturned = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val secondReadReturned = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val secondReadTerminal = CompletableDeferred<MiniHomePublicationReadTerminalOutcome>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    beforePublicationRead = { _, readIdentity ->
                        if (readIdentity.value == 2L) {
                            secondReadEntered.complete(readIdentity)
                            releaseSecondReadCallback.await()
                        }
                    },
                    afterPublicationRead = { _, readIdentity ->
                        when (readIdentity.value) {
                            1L -> firstReadReturned.complete(readIdentity)
                            2L -> secondReadReturned.complete(readIdentity)
                        }
                    },
                    onPublicationReadTerminal = { _, readIdentity, outcome ->
                        if (readIdentity.value == 2L) secondReadTerminal.complete(outcome)
                    },
                )
            val transactionHeld = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()

            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { loadDispatcher ->
                val loading = async(loadDispatcher) { repository.load() }
                val completion = CompletableDeferred<Throwable?>()
                loading.invokeOnCompletion(completion::complete)
                val readIdentity = secondReadEntered.await()
                val transaction =
                    async(Dispatchers.IO) {
                        database.withTransaction {
                            database.cacheDao().clearMiniHome("second-publication-read-gate")
                            transactionHeld.complete(Unit)
                            releaseTransaction.await()
                        }
                    }
                transactionHeld.await()

                try {
                    releaseSecondReadCallback.complete(Unit)
                    val loadDispatcherDrained = CompletableDeferred<Unit>()
                    launch(loadDispatcher) { loadDispatcherDrained.complete(Unit) }
                    loadDispatcherDrained.await()

                    assertEquals(MiniHomePublicationReadIdentity(1L), firstReadReturned.await())
                    assertEquals(MiniHomePublicationReadIdentity(2L), readIdentity)
                    assertFalse(secondReadReturned.isCompleted)
                    assertFalse(loading.isCompleted)

                    val expected = CancellationException("cancel suspended publication read")
                    loading.cancel(expected)
                    val cancellationDispatcherDrained = CompletableDeferred<Unit>()
                    launch(loadDispatcher) { cancellationDispatcherDrained.complete(Unit) }
                    cancellationDispatcherDrained.await()

                    assertTrue(loading.isCompleted)
                    assertSame(expected, completion.await())
                    val actual =
                        try {
                            loading.await()
                            throw AssertionError("Expected cancellation")
                        } catch (failure: CancellationException) {
                            failure
                        }
                    assertEquals(expected.javaClass, actual.javaClass)
                    assertEquals(expected.message, actual.message)
                    assertFalse(secondReadReturned.isCompleted)
                    val terminal = secondReadTerminal.await()
                    assertTrue(terminal is MiniHomePublicationReadTerminalOutcome.Cancelled)
                    assertSame(
                        completion.await(),
                        (terminal as MiniHomePublicationReadTerminalOutcome.Cancelled).failure,
                    )
                } finally {
                    releaseTransaction.complete(Unit)
                    transaction.await()
                    loading.cancel()
                }
            }
        }

    @Test
    fun `second publication read returns after Room transaction releases before load finalizes`() =
        runTest {
            val secondReadEntered = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val releaseSecondReadCallback = CompletableDeferred<Unit>()
            val firstReadReturned = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val secondReadReturned = CompletableDeferred<MiniHomePublicationReadIdentity>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    beforePublicationRead = { _, readIdentity ->
                        if (readIdentity.value == 2L) {
                            secondReadEntered.complete(readIdentity)
                            releaseSecondReadCallback.await()
                        }
                    },
                    afterPublicationRead = { _, readIdentity ->
                        when (readIdentity.value) {
                            1L -> firstReadReturned.complete(readIdentity)
                            2L -> secondReadReturned.complete(readIdentity)
                        }
                    },
                )
            val transactionHeld = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()

            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { loadDispatcher ->
                val loading = async(loadDispatcher) { repository.load() }
                val completion = CompletableDeferred<Throwable?>()
                loading.invokeOnCompletion(completion::complete)
                val readIdentity = secondReadEntered.await()
                val transaction =
                    async(Dispatchers.IO) {
                        database.withTransaction {
                            database
                                .cacheDao()
                                .clearMiniHome("second-publication-read-release-gate")
                            transactionHeld.complete(Unit)
                            releaseTransaction.await()
                        }
                    }
                transactionHeld.await()

                releaseSecondReadCallback.complete(Unit)
                val loadDispatcherDrained = CompletableDeferred<Unit>()
                launch(loadDispatcher) { loadDispatcherDrained.complete(Unit) }
                loadDispatcherDrained.await()

                assertEquals(MiniHomePublicationReadIdentity(1L), firstReadReturned.await())
                assertEquals(MiniHomePublicationReadIdentity(2L), readIdentity)
                assertFalse(secondReadReturned.isCompleted)
                assertFalse(loading.isCompleted)

                releaseTransaction.complete(Unit)
                transaction.await()
                val loaded = loading.await() as MiniHomeLoadResult.Ready

                assertTrue(secondReadReturned.isCompleted)
                assertEquals(MiniHomePublicationReadIdentity(2L), secondReadReturned.await())
                assertEquals(null, completion.await())
                assertEquals(Revision(7), loaded.committed.revision)
                assertFalse(loaded.stale)
            }
        }

    @Test
    fun `cache diagnostic runtime exception cannot change successful load`() = runTest {
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                beforeCacheApply = { throw IllegalStateException("diagnostic runtime") },
            )

        val loaded = repository.load() as MiniHomeLoadResult.Ready

        assertEquals(Revision(7), loaded.committed.revision)
        assertFalse(loaded.stale)
    }

    @Test
    fun `injected transaction observer faults cannot change successful load`() = runTest {
        val failures = mutableListOf<Throwable>()
        val injected =
            ArrayDeque<Throwable>().apply {
                add(IllegalStateException("trace runtime"))
                add(AssertionError("trace assertion"))
                add(CancellationException("trace cancelled"))
            }
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                onCacheTransactionDiagnostic = {
                    if (injected.isNotEmpty()) throw injected.removeFirst()
                },
                onDiagnosticFailure = { failures += it },
            )

        val loaded = repository.load() as MiniHomeLoadResult.Ready

        assertEquals(Revision(7), loaded.committed.revision)
        assertFalse(loaded.stale)
        assertEquals(
            listOf(
                IllegalStateException::class.java,
                AssertionError::class.java,
                CancellationException::class.java,
            ),
            failures.map { it.javaClass },
        )
    }

    @Test
    fun `cache transaction cancellation terminal preserves the exact delegate failure`() = runTest {
        val expected = CancellationException("cache transaction cancellation")
        val observations = mutableListOf<MiniHomeCacheTransactionDiagnosticObservation>()
        val installation = MiniHomeCacheConflictDiagnostics.install { throw expected }
        try {
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    onCacheTransactionDiagnostic = observations::add,
                )

            val actual =
                try {
                    repository.load()
                    throw AssertionError("Expected cancellation")
                } catch (failure: CancellationException) {
                    failure
                }

            assertEquals(expected.message, actual.message)
        } finally {
            installation.close()
        }

        assertEquals(
            listOf(
                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED,
                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_ENTERED,
                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CANCELLED,
            ),
            observations.map { it.stage },
        )
        assertSame(expected, observations.last().failure)
    }

    @Test
    fun `injected publication observer faults cannot change legacy result`() = runTest {
        val legacyReturns = mutableListOf<MiniHomePublicationReadIdentity>()
        val failures = mutableListOf<Throwable>()
        val injected =
            ArrayDeque<Throwable>().apply {
                add(IllegalStateException("publication runtime"))
                add(CancellationException("publication trace cancelled"))
            }
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                afterPublicationRead = { _, readIdentity -> legacyReturns += readIdentity },
                onPublicationReadTerminal = { _, _, _ ->
                    throw injected.removeFirst()
                },
                onDiagnosticFailure = { failures += it },
            )

        val loaded = repository.load() as MiniHomeLoadResult.Ready

        assertEquals(Revision(7), loaded.committed.revision)
        assertEquals(
            listOf(MiniHomePublicationReadIdentity(1), MiniHomePublicationReadIdentity(2)),
            legacyReturns,
        )
        assertEquals(2, failures.size)
        assertEquals(IllegalStateException::class.java, failures[0].javaClass)
        assertEquals(CancellationException::class.java, failures[1].javaClass)
    }

    @Test
    fun `cache load exposes transaction body and terminal separately from outer cache callback`() =
        runTest {
            val observations = mutableListOf<String>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    beforeCacheApply = { observations += "legacy-cache-entered" },
                    afterCacheApply = { _, current ->
                        observations +=
                            "legacy-cache-returned:${if (current) "CURRENT" else "CONFLICT"}"
                    },
                    onCacheTransactionDiagnostic = { observation ->
                        observations +=
                            when (observation.stage) {
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED,
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_ENTERED,
                                MiniHomeCacheTransactionDiagnosticStage.LAYOUT_APPLY,
                                MiniHomeCacheTransactionDiagnosticStage.INVENTORY_APPLY,
                                MiniHomeCacheTransactionDiagnosticStage.CURRENT_SNAPSHOT,
                                MiniHomeCacheTransactionDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_RETURNED,
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_SCOPE_RETURNED,
                                MiniHomeCacheTransactionDiagnosticStage.TERMINAL_CONFLICT,
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_THREW,
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CANCELLED ->
                                    "${observation.stage.receiptStage}:${observation.accountId.value}:${observation.operationId?.value}"
                                MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_RETURNED ->
                                    "${observation.stage.receiptStage}:${observation.accountId.value}:${observation.operationId?.value}:${observation.result}"
                            }
                    },
                )

            repository.load()

            assertEquals(
                listOf(
                    "legacy-cache-entered",
                    "cache-transaction-call-entered:account-a:null",
                    "cache-transaction-body-entered:account-a:null",
                    "cache-layout-apply:account-a:null",
                    "cache-inventory-apply:account-a:null",
                    "cache-current-snapshot:account-a:null",
                    "cache-verified-inventory-decode:account-a:null",
                    "cache-transaction-body-returned:account-a:null",
                    "cache-transaction-scope-returned:account-a:null",
                    "cache-transaction-returned:account-a:null:CURRENT",
                    "legacy-cache-returned:CURRENT",
                ),
                observations,
            )
        }

    @Test
    fun `publication read exposes an exact terminal separately from legacy returned callback`() =
        runTest {
            val observations = mutableListOf<String>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    afterPublicationRead = { account, readId ->
                        observations += "legacy-returned:${account.value}:${readId.value}"
                    },
                    onPublicationReadTerminal = { account, readId, outcome ->
                        observations +=
                            "publication-read-terminal-${outcome::class.simpleName?.lowercase()}:${account.value}:${readId.value}"
                    },
                )

            repository.load()

            assertEquals(
                listOf(
                    "publication-read-terminal-returned:account-a:1",
                    "legacy-returned:account-a:1",
                    "publication-read-terminal-returned:account-a:2",
                    "legacy-returned:account-a:2",
                ),
                observations,
            )
        }

    @Test
    fun `tagged shared Room writer holds the second publication read until its terminal`() =
        runTest {
            val observations = mutableListOf<RoomTransactionOwnerObservation>()
            val diagnostics = RoomTransactionOwnerDiagnostics(observations::add)
            val startTransaction = CompletableDeferred<Unit>()
            val transactionHeld = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val secondReadEntered = CompletableDeferred<Unit>()
            val secondReadReturned = CompletableDeferred<Unit>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                    beforePublicationRead = { _, readIdentity ->
                        if (readIdentity.value == 2L) {
                            startTransaction.complete(Unit)
                            transactionHeld.await()
                            secondReadEntered.complete(Unit)
                        }
                    },
                    afterPublicationRead = { _, readIdentity ->
                        if (readIdentity.value == 2L) secondReadReturned.complete(Unit)
                    },
                )

            val transaction =
                async(Dispatchers.IO) {
                    startTransaction.await()
                    diagnostics.observe(RoomTransactionOwner.ANALYTICS_ENQUEUE) {
                        database.withTransaction {
                            database.cacheDao().clearMiniHome("tagged-shared-room-holder")
                            transactionHeld.complete(Unit)
                            releaseTransaction.await()
                        }
                    }
                }
            val loading = async { repository.load() }
            secondReadEntered.await()
            transactionHeld.await()

            assertFalse(secondReadReturned.isCompleted)
            assertFalse(loading.isCompleted)
            assertEquals(1, observations.size)
            assertTrue(observations.single() is RoomTransactionOwnerObservation.Began)

            releaseTransaction.complete(Unit)
            transaction.await()
            val loaded = loading.await() as MiniHomeLoadResult.Ready

            assertTrue(secondReadReturned.isCompleted)
            assertEquals(2, observations.size)
            assertEquals(observations[0].token, observations[1].token)
            assertTrue(observations[1] is RoomTransactionOwnerObservation.Returned)
            assertEquals(Revision(7), loaded.committed.revision)
            assertFalse(loaded.stale)
        }

    @Test
    fun `cache diagnostic assertion cannot change successful load`() = runTest {
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                afterCacheApply = { _, _ -> throw AssertionError("diagnostic assertion") },
            )

        val loaded = repository.load() as MiniHomeLoadResult.Ready

        assertEquals(Revision(7), loaded.committed.revision)
        assertFalse(loaded.stale)
    }

    @Test
    fun `cache diagnostic cancellation remains the exact primary failure`() = runTest {
        val expected = CancellationException("diagnostic cancellation")
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                beforeCacheApply = { throw expected },
            )

        val actual =
            try {
                repository.load()
                throw AssertionError("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        assertSame(expected, actual)
    }

    @Test
    fun `real Room decode mutations select the exact first verifier field and restore`() = runTest {
        val account = AccountId("account-a")
        val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
        val baselineInventory =
            authoritativeInventory(
                    "decode-item",
                    "Decode item",
                    ItemCategory.DECORATION,
                    testMiniHomeMediaIdentity("decode-item"),
                    generation = 2,
                )
                .cacheWrite(snapshotToken(2), 2)
        database.cacheDao().applyAuthoritativeMiniHome(cacheLayoutWrite(layout(1), 2))
        database.cacheDao().applyAuthoritativeInventory(baselineInventory)
        val baseline = requireNotNull(database.cacheDao().currentInventoryCache(account.value))
        val mutations =
            linkedMapOf<String, (CachedInventoryState) -> CachedInventoryState>(
                "watermark.snapshotHash.format" to
                    { state ->
                        state.copy(watermark = state.watermark.copy(snapshotHash = "bad"))
                    },
                "watermark.registeredPlantCount" to
                    { state ->
                        state.copy(watermark = state.watermark.copy(registeredPlantCount = 201))
                    },
                "watermark.loadedAtEpochMillis" to
                    { state ->
                        state.copy(watermark = state.watermark.copy(loadedAtEpochMillis = -1))
                    },
                "catalog.count" to
                    { state ->
                        state.copy(
                            catalog =
                                (0..200).map { index ->
                                    val id = "decode-catalog-$index"
                                    state.catalog
                                        .single()
                                        .copy(
                                            itemId = id,
                                            assetPath =
                                                state.catalog
                                                    .single()
                                                    .assetPath
                                                    .replace(
                                                        "decode-item",
                                                        id,
                                                    ),
                                        )
                                }
                        )
                    },
                "owned.count" to
                    { state ->
                        state.copy(
                            owned =
                                (0..200).map { index ->
                                    state.owned.single().copy(itemId = "decode-owned-$index")
                                }
                        )
                    },
                "catalog.entity" to
                    { state ->
                        state.copy(catalog = listOf(state.catalog.single().copy(name = "")))
                    },
                "owned.entity" to
                    { state ->
                        state.copy(
                            owned = listOf(state.owned.single().copy(availability = "BROKEN"))
                        )
                    },
                "owned.catalogAvailability" to
                    { state ->
                        state.copy(owned = listOf(state.owned.single().copy(itemId = "not-public")))
                    },
                "partial.unavailableOwned" to
                    { state ->
                        state.copy(
                            owned =
                                listOf(
                                    state.owned
                                        .single()
                                        .copy(
                                            itemId = "not-public",
                                            availability = "UNAVAILABLE",
                                        )
                                )
                        )
                    },
                "watermark.snapshotHash.content" to
                    { state ->
                        state.copy(watermark = state.watermark.copy(snapshotHash = "b".repeat(64)))
                    },
            )

        suspend fun replace(state: CachedInventoryState) {
            database.withTransaction {
                database.cacheDao().clearShopItems(account.value)
                database.cacheDao().clearOwnedItems(account.value)
                database.cacheDao().clearInventorySnapshotWatermark(account.value)
                database.cacheDao().upsertShopItems(state.catalog)
                database.cacheDao().upsertOwnedItems(state.owned)
                database.cacheDao().upsertInventorySnapshotWatermark(state.watermark)
            }
        }

        mutations.forEach { (expectedField, mutate) ->
            replace(mutate(baseline))
            val observations = mutableListOf<MiniHomeCacheDiagnosticObservation>()
            val transactionObservations =
                mutableListOf<MiniHomeCacheTransactionDiagnosticObservation>()
            val installation = MiniHomeCacheConflictDiagnostics.install(observations::add)
            try {
                FirebaseMiniHomeRepository(
                        database,
                        remote,
                        onCacheTransactionDiagnostic = transactionObservations::add,
                    )
                    .load()
            } finally {
                installation.close()
            }
            val terminal = observations.single {
                it.stage == MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT
            }
            assertEquals(
                MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
                terminal.category,
            )
            assertEquals(expectedField, terminal.operands["field"])
            val transactionConflict = transactionObservations.single {
                it.stage == MiniHomeCacheTransactionDiagnosticStage.VERIFIED_INVENTORY_DECODE
            }
            assertEquals(
                MiniHomeCacheDiagnosticOutcome.CONFLICT,
                transactionConflict.cacheObservation?.outcome,
            )
            assertEquals(
                expectedField,
                transactionConflict.cacheObservation?.operands?.get("field"),
            )
            replace(baseline)
            assertEquals(baseline, database.cacheDao().currentInventoryCache(account.value))
        }
    }

    @Test
    fun `real Room migrated unverified layout and inventory close an applied cache receipt`() =
        runTest {
            val account = AccountId("account-a")
            val legacyHome =
                CachedMiniHomeEntity(
                    accountId = account.value,
                    miniHomeId = "home-a",
                    name = "Legacy room",
                    placedPlantCount = 1,
                    revision = 1,
                    updatedAtEpochMillis = 1,
                )
            database.cacheDao().upsertMiniHome(legacyHome)
            database
                .cacheDao()
                .upsertMiniHomePlacements(
                    listOf(
                        CachedMiniHomePlacementEntity(
                            accountId = account.value,
                            placementId = "legacy-placement",
                            miniHomeId = legacyHome.miniHomeId,
                            plantId = "plant-a",
                            itemId = null,
                            normalizedX = 0.5,
                            normalizedY = 0.5,
                            zIndex = 0,
                            layoutRevision = legacyHome.revision,
                        )
                    )
                )
            database
                .cacheDao()
                .upsertMiniHomeCacheWatermark(
                    MiniHomeCacheWatermarkEntity(
                        accountId = account.value,
                        generation = 0,
                        kind = MiniHomeCacheWatermarkKind.PRESENT.name,
                        layoutRevision = legacyHome.revision,
                        miniHomeId = legacyHome.miniHomeId,
                        operationId = null,
                        payloadHash = null,
                        tombstoneId = null,
                        authoritativeAtEpochMillis = legacyHome.updatedAtEpochMillis,
                        verified = false,
                    )
                )
            database
                .cacheDao()
                .upsertShopItems(
                    listOf(
                        CachedShopItemEntity(
                            accountId = account.value,
                            itemId = "legacy-item",
                            name = "Legacy item",
                            description = "Legacy description",
                            category = "DECORATION",
                            assetPath = "catalog-assets/legacy-item/preview.webp",
                            acquisitionCondition = null,
                            revision = 1,
                            updatedAtEpochMillis = 1,
                        )
                    )
                )
            database
                .cacheDao()
                .upsertOwnedItems(
                    listOf(
                        CachedOwnedItemEntity(
                            accountId = account.value,
                            itemId = "legacy-item",
                            acquiredAtEpochMillis = 1,
                            applied = false,
                            revision = 1,
                        )
                    )
                )
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
            val observations = mutableListOf<MiniHomeCacheDiagnosticObservation>()
            val installation = MiniHomeCacheConflictDiagnostics.install(observations::add)
            val loaded =
                try {
                    FirebaseMiniHomeRepository(
                            database,
                            FakeRemote(layout(2)).apply { cacheGeneration = 2 },
                        )
                        .load()
                } finally {
                    installation.close()
                }

            assertTrue(loaded is MiniHomeLoadResult.Ready)
            val receiptOperation = OperationId("real-room-migration-receipt")
            val receipt =
                MiniHomeCacheDiagnosticReceipt(
                    accountId = account,
                    operationId = receiptOperation,
                    observations = observations.map { it.copy(operationId = receiptOperation) },
                    closed = true,
                )
            receipt.requireComplete()
            val layoutApply = observations.single {
                it.stage == MiniHomeCacheDiagnosticStage.LAYOUT_APPLY
            }
            val inventoryApply = observations.single {
                it.stage == MiniHomeCacheDiagnosticStage.INVENTORY_APPLY
            }
            assertEquals(MiniHomeCacheDiagnosticOutcome.APPLIED, layoutApply.outcome)
            assertEquals("0", layoutApply.operands["before.generation"])
            assertEquals(null, layoutApply.operands["before.operationId"])
            assertEquals(null, layoutApply.operands["before.payloadHash"])
            assertEquals(MiniHomeCacheDiagnosticOutcome.APPLIED, inventoryApply.outcome)
            assertEquals("0", inventoryApply.operands["before.generation"])
            assertEquals("0".repeat(64), inventoryApply.operands["before.snapshotHash"])
            assertEquals("true", inventoryApply.operands["before.partial"])
        }

    @Test
    fun `publication return runtime and assertion faults cannot change successful load`() =
        runTest {
            listOf<Throwable>(
                    IllegalStateException("publication runtime"),
                    AssertionError("publication assertion"),
                )
                .forEach { observerFailure ->
                    val repository =
                        FirebaseMiniHomeRepository(
                            database,
                            FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                            afterPublicationRead = { _, _ -> throw observerFailure },
                        )

                    val loaded = repository.load() as MiniHomeLoadResult.Ready

                    assertEquals(Revision(7), loaded.committed.revision)
                    assertFalse(loaded.stale)
                }
        }

    @Test
    fun `publication return cancellation remains the exact primary failure`() = runTest {
        val expected = CancellationException("publication cancellation")
        val repository =
            FirebaseMiniHomeRepository(
                database,
                FakeRemote(layout(7)).apply { cacheGeneration = 7 },
                afterPublicationRead = { _, _ -> throw expected },
            )

        val actual =
            try {
                repository.load()
                throw AssertionError("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        assertSame(expected, actual)
    }

    @Test
    fun `failure rereads Room winner instead of publishing cache captured before remote`() =
        runTest {
            val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
            val repository = FirebaseMiniHomeRepository(database, remote)
            assertEquals(
                Revision(1),
                (repository.load() as MiniHomeLoadResult.Ready).committed.revision,
            )
            val remoteStarted = CompletableDeferred<Unit>()
            val releaseFailure = CompletableDeferred<Unit>()
            remote.onLoad = {
                remoteStarted.complete(Unit)
                releaseFailure.await()
                throw IOException("offline after concurrent sync")
            }

            val loading = async { repository.load() }
            remoteStarted.await()
            applyCoherentRoomSnapshot(database, layout(2), 2)
            releaseFailure.complete(Unit)

            val loaded = loading.await() as MiniHomeLoadResult.Ready
            assertTrue(loaded.stale)
            assertEquals(Revision(2), loaded.committed.revision)
            assertEquals(
                Revision(2),
                Revision(requireNotNull(database.cacheDao().miniHome("account-a")).revision),
            )
        }

    @Test
    fun `old remote success returns concurrent newer Room winner without stale decoration`() =
        runTest {
            val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
            val repository = FirebaseMiniHomeRepository(database, remote)
            repository.load()
            val remoteStarted = CompletableDeferred<Unit>()
            val releaseSuccess = CompletableDeferred<Unit>()
            remote.onLoad = {
                remoteStarted.complete(Unit)
                releaseSuccess.await()
            }

            val loading = async { repository.load() }
            remoteStarted.await()
            applyCoherentRoomSnapshot(database, layout(2), 2)
            releaseSuccess.complete(Unit)

            val loaded = loading.await() as MiniHomeLoadResult.Ready
            assertFalse(loaded.stale)
            assertEquals(Revision(2), loaded.committed.revision)
            assertEquals("저장된 방", loaded.committed.name)
            assertEquals(2L, database.cacheDao().miniHome("account-a")?.revision)
        }

    @Test
    fun `owner switch at failure-time Room reread forbids old owner publication`() = runTest {
        val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
        var gateRead = false
        val rereadEntered = CompletableDeferred<Unit>()
        val releaseReread = CompletableDeferred<Unit>()
        val repository =
            FirebaseMiniHomeRepository(
                database,
                remote,
                beforePublicationRead = { _, _ ->
                    if (gateRead) {
                        rereadEntered.complete(Unit)
                        releaseReread.await()
                    }
                },
            )
        repository.load()
        remote.onLoad = { throw IOException("offline") }
        gateRead = true

        val loading = async { repository.load() }
        rereadEntered.await()
        remote.account = AccountId("account-b")
        releaseReread.complete(Unit)

        assertEquals(MiniHomeLoadResult.Forbidden, loading.await())
    }

    @Test
    fun `deletion tombstone committed during failure wins over captured layout`() = runTest {
        val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
        val repository = FirebaseMiniHomeRepository(database, remote)
        repository.load()
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        remote.onLoad = {
            remoteStarted.complete(Unit)
            releaseFailure.await()
            throw IOException("offline after deletion")
        }

        val loading = async { repository.load() }
        remoteStarted.await()
        database.withTransaction {
            val inventory =
                requireNotNull(
                    database
                        .cacheDao()
                        .currentInventoryCache("account-a")
                        ?.verifiedAuthoritativeInventoryOrNull(AccountId("account-a"))
                )
            database
                .cacheDao()
                .applyAuthoritativeMiniHome(
                    AuthoritativeMiniHomeCacheWrite.Deletion(
                        "account-a",
                        2,
                        "deletion-generation-two",
                        2,
                        snapshotToken(2),
                        2,
                    )
                )
            database
                .cacheDao()
                .applyAuthoritativeInventory(inventory.cacheWrite(snapshotToken(2), 2))
        }
        releaseFailure.complete(Unit)

        val loaded = loading.await() as MiniHomeLoadResult.Ready
        assertTrue(loaded.stale)
        assertEquals(Revision(0), loaded.committed.revision)
        assertEquals("나의 미니 식물원", loaded.committed.name)
        assertNull(database.cacheDao().miniHome("account-a"))
        assertEquals(
            MiniHomeCacheWatermarkKind.DELETED.name,
            database.cacheDao().miniHomeCacheWatermark("account-a")?.kind,
        )
    }

    @Test
    fun `failure-time atomic reread cannot publish torn layout and inventory revisions`() =
        runTest {
            val firstIdentity = testMiniHomeMediaIdentity("coherent-first", "first")
            val secondIdentity = testMiniHomeMediaIdentity("coherent-second", "second")
            val remote =
                FakeRemote(layout(1)).apply {
                    cacheGeneration = 1
                    authoritativeInventory =
                        authoritativeInventory(
                            "coherent-first",
                            "첫 장식",
                            ItemCategory.DECORATION,
                            firstIdentity,
                            generation = 1,
                        )
                }
            var watchReread = false
            val rereadAttempted = CompletableDeferred<Unit>()
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    remote,
                    beforePublicationRead = { _, _ ->
                        if (watchReread) rereadAttempted.complete(Unit)
                    },
                )
            repository.load()
            val remoteStarted = CompletableDeferred<Unit>()
            val releaseFailure = CompletableDeferred<Unit>()
            remote.onLoad = {
                remoteStarted.complete(Unit)
                releaseFailure.await()
                throw IOException("offline during coherent sync")
            }
            watchReread = true
            val loading = async { repository.load() }
            remoteStarted.await()

            val layoutWritten = CompletableDeferred<Unit>()
            val releaseInventory = CountDownLatch(1)
            val writer =
                async(Dispatchers.IO) {
                    database.withTransaction {
                        database
                            .cacheDao()
                            .applyAuthoritativeMiniHome(cacheLayoutWrite(layout(2), 2))
                        layoutWritten.complete(Unit)
                        check(releaseInventory.await(10, TimeUnit.SECONDS)) {
                            "inventory write barrier was not released"
                        }
                        database
                            .cacheDao()
                            .applyAuthoritativeInventory(
                                authoritativeInventory(
                                        "coherent-second",
                                        "둘째 장식",
                                        ItemCategory.DECORATION,
                                        secondIdentity,
                                        generation = 2,
                                    )
                                    .cacheWrite(snapshotToken(2), 2)
                            )
                    }
                }

            layoutWritten.await()
            releaseFailure.complete(Unit)
            rereadAttempted.await()
            releaseInventory.countDown()
            writer.await()

            val loaded = loading.await() as MiniHomeLoadResult.Ready
            assertTrue(loaded.stale)
            assertEquals(Revision(2), loaded.committed.revision)
            assertEquals(listOf("coherent-second"), loaded.decorations.map { it.id.value })
            assertFalse(loaded.decorations.single().availableForApplication)
            assertEquals(
                2L,
                database.cacheDao().inventorySnapshotWatermark("account-a")?.generation,
            )
        }

    @Test
    fun `two concurrent failed loads both publish the newer Room winner`() = runTest {
        val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
        val repository = FirebaseMiniHomeRepository(database, remote)
        repository.load()
        val firstRemoteStarted = CompletableDeferred<Unit>()
        val releaseFirstFailure = CompletableDeferred<Unit>()
        var delayedLoad = 0
        remote.onLoad = {
            delayedLoad += 1
            if (delayedLoad == 1) {
                firstRemoteStarted.complete(Unit)
                releaseFirstFailure.await()
            }
            throw IOException("offline load $delayedLoad")
        }

        val first = async { repository.load() }
        firstRemoteStarted.await()
        val second = async { repository.load() }
        applyCoherentRoomSnapshot(database, layout(2), 2)
        releaseFirstFailure.complete(Unit)

        val results = listOf(first.await(), second.await()).map { it as MiniHomeLoadResult.Ready }
        assertTrue(results.all { it.stale })
        assertEquals(listOf(Revision(2), Revision(2)), results.map { it.committed.revision })
        assertEquals(2, delayedLoad)
    }

    @Test
    fun `process restart offline fallback publishes persisted newer Room winner`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "mini-home-monotonic-fallback-restart.db"
        context.deleteDatabase(name)
        val remote = FakeRemote(layout(1)).apply { cacheGeneration = 1 }
        var first: PlanteriorDatabase? =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
        var second: PlanteriorDatabase? = null
        try {
            FirebaseMiniHomeRepository(requireNotNull(first), remote).load()
            applyCoherentRoomSnapshot(first, layout(2), 2)
            first.close()
            first = null
            remote.failLoad = true
            second =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()

            val loaded =
                FirebaseMiniHomeRepository(requireNotNull(second), remote).load()
                    as MiniHomeLoadResult.Ready

            assertTrue(loaded.stale)
            assertEquals(Revision(2), loaded.committed.revision)
            assertEquals(2L, second.cacheDao().miniHome("account-a")?.revision)
        } finally {
            first?.close()
            second?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `online inventory survives repository restart and restores exact digest background offline`() =
        runTest {
            val identity = testMiniHomeMediaIdentity("durable-background", "durable bytes")
            val remote =
                FakeRemote(layout(3)).apply {
                    authoritativeInventory =
                        authoritativeInventory(
                            "durable-background",
                            "지속되는 배경",
                            ItemCategory.BACKGROUND,
                            identity,
                        )
                }

            val online = FirebaseMiniHomeRepository(database, remote).load()
            remote.failLoad = true
            val offline = FirebaseMiniHomeRepository(database, remote).load()

            assertEquals(
                identity,
                (online as MiniHomeLoadResult.Ready).decorations.single().mediaIdentity,
            )
            assertEquals(
                identity,
                (offline as MiniHomeLoadResult.Ready).decorations.single().mediaIdentity,
            )
            assertTrue(offline.stale)
            assertFalse(offline.decorations.single().availableForApplication)
        }

    @Test
    fun `disk Room recreation restores verified inventory and layout offline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "mini-home-inventory-restart.db"
        context.deleteDatabase(name)
        val remote =
            FakeRemote(layout(5)).apply {
                authoritativeInventory =
                    authoritativeInventory(
                        "restart-furniture",
                        "재시작 가구",
                        ItemCategory.FURNITURE,
                        testMiniHomeMediaIdentity("restart-furniture", "restart"),
                        generation = 6,
                    )
            }
        var first: PlanteriorDatabase? =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
        var second: PlanteriorDatabase? = null
        try {
            FirebaseMiniHomeRepository(requireNotNull(first), remote).load()
            first.close()
            first = null
            remote.failLoad = true
            second =
                Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()

            val restored =
                FirebaseMiniHomeRepository(requireNotNull(second), remote).load()
                    as MiniHomeLoadResult.Ready

            assertTrue(restored.stale)
            assertEquals(Revision(5), restored.committed.revision)
            assertEquals(ItemCategory.FURNITURE, restored.decorations.single().category)
            assertEquals(
                testMiniHomeMediaIdentity("restart-furniture", "restart"),
                restored.decorations.single().mediaIdentity,
            )
        } finally {
            first?.close()
            second?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `same inventory generation conflict rolls back newer layout and returns coherent cache`() =
        runTest {
            val transactionStages = mutableListOf<MiniHomeCacheTransactionDiagnosticObservation>()
            val remote =
                FakeRemote(layout(3)).apply {
                    authoritativeInventory =
                        authoritativeInventory(
                            "stable-background",
                            "안정 배경",
                            ItemCategory.BACKGROUND,
                            testMiniHomeMediaIdentity("stable-background", "stable"),
                            generation = 2,
                        )
                }
            val repository =
                FirebaseMiniHomeRepository(
                    database,
                    remote,
                    onCacheTransactionDiagnostic = transactionStages::add,
                )
            repository.load()
            transactionStages.clear()
            remote.layout = layout(4).copy(name = "찢어진 새 배치")
            remote.cacheGeneration = 4
            remote.authoritativeInventory =
                authoritativeInventory(
                    "conflicting-background",
                    "충돌 배경",
                    ItemCategory.BACKGROUND,
                    testMiniHomeMediaIdentity("conflicting-background", "conflict"),
                    generation = 2,
                )

            val recovered = repository.load() as MiniHomeLoadResult.Ready

            assertTrue(recovered.stale)
            assertEquals(Revision(3), recovered.committed.revision)
            assertEquals("저장된 방", recovered.committed.name)
            assertEquals(listOf("stable-background"), recovered.decorations.map { it.id.value })
            assertEquals(3L, database.cacheDao().miniHome("account-a")?.revision)
            assertEquals(
                listOf(
                    MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED,
                    MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_ENTERED,
                    MiniHomeCacheTransactionDiagnosticStage.LAYOUT_APPLY,
                    MiniHomeCacheTransactionDiagnosticStage.INVENTORY_APPLY,
                    MiniHomeCacheTransactionDiagnosticStage.TERMINAL_CONFLICT,
                    MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_RETURNED,
                ),
                transactionStages.map { it.stage },
            )
            assertEquals(
                MiniHomeCacheTransactionResult.CONFLICT,
                transactionStages.last().result,
            )
        }

    @Test
    fun `mixed envelope with delayed inventory rolls back newer layout and keeps coherent cache`() =
        runTest {
            val remote =
                FakeRemote(layout(3)).apply {
                    authoritativeInventory =
                        authoritativeInventory(
                            "current-decoration",
                            "현재 장식",
                            ItemCategory.DECORATION,
                            testMiniHomeMediaIdentity("current-decoration", "current"),
                            generation = 3,
                        )
                }
            val repository = FirebaseMiniHomeRepository(database, remote)
            repository.load()
            remote.layout = layout(4).copy(name = "새 배치")
            remote.cacheGeneration = 4
            remote.authoritativeInventory =
                authoritativeInventory(
                    "delayed-decoration",
                    "지연 장식",
                    ItemCategory.DECORATION,
                    testMiniHomeMediaIdentity("delayed-decoration", "delayed"),
                    generation = 2,
                )

            val loaded = repository.load() as MiniHomeLoadResult.Ready

            assertTrue(loaded.stale)
            assertEquals(Revision(3), loaded.committed.revision)
            assertEquals(listOf("current-decoration"), loaded.decorations.map { it.id.value })
            assertEquals(
                3L,
                database.cacheDao().inventorySnapshotWatermark("account-a")?.generation,
            )
        }

    @Test
    fun `offline inventory fallback preserves background removal metadata but never enables stale application`() =
        runTest {
            val account = AccountId("account-a")
            val backgroundIdentity = testMiniHomeMediaIdentity("cached-background", "cached")
            val deletedIdentity = testMiniHomeMediaIdentity("deleted-decoration", "deleted")
            val catalog =
                listOf(
                    AuthoritativeCatalogItem(
                        ItemId("cached-background"),
                        "저장된 배경",
                        "이전 공개 배경",
                        ItemCategory.BACKGROUND,
                        backgroundIdentity,
                        null,
                        Revision(2),
                        20,
                    )
                )
            val owned =
                listOf(
                    AuthoritativeOwnedItem(
                        ItemId("cached-background"),
                        21,
                        true,
                        Revision(3),
                        AuthoritativeInventoryAvailability.AVAILABLE,
                        null,
                    ),
                    AuthoritativeOwnedItem(
                        ItemId("deleted-decoration"),
                        10,
                        true,
                        Revision(4),
                        AuthoritativeInventoryAvailability.UNAVAILABLE,
                        AuthoritativeOwnedCatalogSnapshot(
                            "삭제된 장식",
                            ItemCategory.DECORATION,
                            deletedIdentity,
                            Revision(3),
                        ),
                    ),
                )
            val cachedInventory =
                AuthoritativeInventory(
                    INVENTORY_CONTRACT_VERSION,
                    account,
                    catalog,
                    owned,
                    1,
                    21,
                    partial = true,
                    generation = 1,
                    snapshotHash =
                        authoritativeInventorySnapshotHash(
                            account,
                            catalog,
                            owned,
                            1,
                            true,
                        ),
                )
            val remote = FakeRemote(layout(3)).apply { authoritativeInventory = cachedInventory }
            val repository = FirebaseMiniHomeRepository(database, remote)
            repository.load()
            remote.failLoad = true

            val loaded = repository.load() as MiniHomeLoadResult.Ready

            assertTrue(loaded.stale)
            assertEquals(2, loaded.decorations.size)
            assertEquals(ItemCategory.BACKGROUND, loaded.decorations[0].category)
            assertEquals(backgroundIdentity.path, loaded.decorations[0].assetPath)
            assertTrue(loaded.decorations.none { it.availableForApplication })
            assertEquals("삭제된 장식", loaded.decorations[1].displayName)

            remote.failLoad = false
            remote.authoritativeInventory =
                authoritativeInventory(
                    "fresh-background",
                    "새로 획득한 배경",
                    ItemCategory.BACKGROUND,
                    testMiniHomeMediaIdentity("fresh-background", "fresh"),
                    generation = 2,
                )
            val refreshed =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
            assertFalse(refreshed.stale)
            assertEquals(listOf("fresh-background"), refreshed.decorations.map { it.id.value })
            assertTrue(refreshed.decorations.single().availableForApplication)
        }

    @Test
    fun `offline migrated cache without an envelope token fails closed then online rebuilds`() =
        runTest {
            database
                .cacheDao()
                .upsertMiniHome(
                    CachedMiniHomeEntity(
                        "account-a",
                        "home-a",
                        "migrated revision three",
                        0,
                        3,
                        300,
                    )
                )
            database
                .cacheDao()
                .upsertMiniHomeCacheWatermark(
                    MiniHomeCacheWatermarkEntity(
                        "account-a",
                        2,
                        MiniHomeCacheWatermarkKind.PRESENT.name,
                        3,
                        "home-a",
                        null,
                        null,
                        null,
                        300,
                        verified = false,
                    )
                )
            var offline = true
            val remote =
                object : MiniHomeRemoteDataSource {
                    override fun activeAccount() = AccountId("account-a")

                    override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
                        if (offline) throw IOException("offline")
                        return RemoteMiniHomeSnapshot(
                            accountId,
                            null,
                            emptyList(),
                            emptyList(),
                            cacheGeneration = 1,
                            deletionTombstoneId = "initial-missing",
                            authoritativeAtEpochMillis = 400,
                            authoritativeInventory = emptyAuthoritativeInventory(accountId),
                        )
                    }

                    override suspend fun save(
                        request: MiniHomeSaveRequest
                    ): RemoteMiniHomeSaveResult = error("not used")
                }
            val repository = FirebaseMiniHomeRepository(database, remote)

            assertEquals(MiniHomeLoadResult.Failed, repository.load())
            assertNull(database.cacheDao().miniHome("account-a"))
            offline = false
            val bootstrapped = repository.load() as MiniHomeLoadResult.Ready

            assertFalse(bootstrapped.stale)
            assertEquals(Revision(0), bootstrapped.committed.revision)
            assertNull(database.cacheDao().miniHome("account-a"))
            assertEquals(true, database.cacheDao().miniHomeCacheWatermark("account-a")?.verified)
            assertEquals(1L, database.cacheDao().miniHomeCacheWatermark("account-a")?.generation)
        }

    @Test
    fun `inconsistent read and transaction retry exhaustion preserve cache before revision two`() =
        runTest {
            val revisionOne = layout(1)
            val remote = FakeRemote(revisionOne)
            val repository = FirebaseMiniHomeRepository(database, remote)
            assertEquals(revisionOne, (repository.load() as MiniHomeLoadResult.Ready).committed)
            val revisionTwo =
                layout(2)
                    .copy(
                        name = "revision two",
                        placements =
                            listOf(
                                placement("revision-two-plant", "plant-a", GridPosition(0, 0)),
                                decoration("revision-two-decor", "decor-a", GridPosition(1, 0))
                                    .copy(zIndex = MiniHomeZIndex(1)),
                            ),
                    )
            remote.layout = revisionTwo
            var failedReads = 0
            remote.onLoad = {
                when (failedReads++) {
                    0 ->
                        throw InconsistentMiniHomeLayoutException(
                            "old home with new placement rows"
                        )
                    1 -> throw IOException("authoritative transaction retry attempts exhausted")
                }
            }

            repeat(2) {
                val stale = repository.load() as MiniHomeLoadResult.Ready
                assertTrue(stale.stale)
                assertEquals(revisionOne, stale.committed)
            }
            assertEquals(
                listOf("placement-a"),
                database.cacheDao().miniHomePlacements("account-a", "home-a", 1).map {
                    it.placementId
                },
            )

            val fresh = repository.load() as MiniHomeLoadResult.Ready
            assertFalse(fresh.stale)
            assertEquals(revisionTwo, fresh.committed)
            assertEquals(
                listOf("revision-two-plant", "revision-two-decor"),
                database.cacheDao().miniHomePlacements("account-a", "home-a", 2).map {
                    it.placementId
                },
            )
        }

    @Test
    fun `delayed revision one load publishes cached revision two instead of fetched response`() =
        runTest {
            val remote = FakeRemote(layout(2)).apply { cacheGeneration = 2 }
            val repository = FirebaseMiniHomeRepository(database, remote)
            assertEquals(
                Revision(2),
                (repository.load() as MiniHomeLoadResult.Ready).committed.revision,
            )
            remote.layout = layout(1).copy(name = "delayed revision one")
            remote.cacheGeneration = 1

            val delayed = repository.load() as MiniHomeLoadResult.Ready

            assertEquals(Revision(2), delayed.committed.revision)
            assertEquals("저장된 방", delayed.committed.name)
            assertFalse(delayed.stale)
            assertEquals(2L, database.cacheDao().miniHome("account-a")?.revision)
        }

    @Test
    fun `same generation mismatch fails closed to current offline cache`() = runTest {
        val remote = FakeRemote(layout(2)).apply { cacheGeneration = 2 }
        val repository = FirebaseMiniHomeRepository(database, remote)
        repository.load()
        remote.layout = layout(2).copy(name = "same generation mismatch")

        val loaded = repository.load() as MiniHomeLoadResult.Ready

        assertTrue(loaded.stale)
        assertEquals("저장된 방", loaded.committed.name)
        assertEquals("저장된 방", database.cacheDao().miniHome("account-a")?.name)
    }

    @Test
    fun `online authoritative refresh replaces a non NFC legacy cache before it can crash`() =
        runTest {
            database
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "e\u0301", 0, 2, 2))
            val remote = FakeRemote(layout(3).copy(name = "권위 있는 방"))

            val loaded =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

            assertEquals(1, remote.loadCalls)
            assertEquals("권위 있는 방", loaded.committed.name)
            assertEquals("권위 있는 방", database.cacheDao().miniHome("account-a")?.name)
        }

    @Test
    fun `valid authoritative refresh transactionally replaces irrecoverable legacy cache`() =
        runTest {
            database
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "A\u0000B", 0, 2, 2))
            val remote = FakeRemote(layout(3).copy(name = "권위 있는 복구"))

            val loaded =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

            assertEquals("권위 있는 복구", loaded.committed.name)
            assertEquals("권위 있는 복구", database.cacheDao().miniHome("account-a")?.name)
        }

    @Test
    fun `recoverable legacy authoritative name is normalized before transactional caching`() =
        runTest {
            val remote = FakeRemote(layout(3).copy(name = "e\u0301"))

            val loaded =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

            assertEquals("é", loaded.committed.name)
            assertEquals("é", database.cacheDao().miniHome("account-a")?.name)
        }

    @Test
    fun `invalid remote cannot publish legacy cache without an envelope token`() = runTest {
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "안전한 캐시", 0, 2, 2))
        val remote = FakeRemote(layout(3).copy(name = "A\u202EB"))

        val loaded = FirebaseMiniHomeRepository(database, remote).load()

        assertEquals(MiniHomeLoadResult.Failed, loaded)
        assertNull(database.cacheDao().miniHome("account-a"))
    }

    @Test
    fun `offline recoverable legacy cache without an envelope token fails closed`() = runTest {
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "e\u0301", 0, 2, 2))
        val remote = FakeRemote(layout(3)).apply { failLoad = true }

        val loaded = FirebaseMiniHomeRepository(database, remote).load()

        assertEquals(1, remote.loadCalls)
        assertEquals(MiniHomeLoadResult.Failed, loaded)
        assertNull(database.cacheDao().miniHome("account-a"))
    }

    @Test
    fun `offline irrecoverable legacy caches are quarantined and return typed failure`() = runTest {
        val invalidNames = listOf("A\u0000B", "A\u202EB", "x".repeat(101))
        invalidNames.forEachIndexed { index, name ->
            database
                .cacheDao()
                .upsertMiniHome(
                    CachedMiniHomeEntity("account-a", "home-a", name, 0, index.toLong(), 2)
                )
            val remote = FakeRemote(layout(3)).apply { failLoad = true }

            assertEquals(
                "case=$index",
                MiniHomeLoadResult.Failed,
                FirebaseMiniHomeRepository(database, remote).load(),
            )
            assertEquals(1, remote.loadCalls)
            assertNull(database.cacheDao().miniHome("account-a"))
        }
    }

    @Test
    fun `forged stored hash and matching forged receipt cannot adopt mutated raw envelope`() =
        runTest {
            val operation = OperationId("operation-forged-hash")
            val payload = legacyPayload(operation, "원래 원문")
            val recomputedHash = exactLegacyHash(payload)
            val forgedHash = "f".repeat(64)
            enqueueRaw(operation, payload, forgedHash)
            val remote = FakeRemote(layout(4).copy(name = "원래 원문"))
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = forgedHash

            repeat(2) { attempt ->
                if (attempt == 1) remote.failLoad = true
                val loaded =
                    FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
                val pending = requireNotNull(loaded.pending)
                val details = requireNotNull(pending.reconciliationDetails)

                assertEquals(MiniHomePendingState.RECONCILIATION_REQUIRED, pending.state)
                assertEquals(MiniHomeSaveFailure.PAYLOAD_MISMATCH, pending.failure)
                assertEquals(forgedHash, details.storedPayloadHash)
                assertEquals(recomputedHash, details.recomputedPayloadHash)
                assertEquals(forgedHash, details.authoritativePayloadHash)
                assertNotNull(database.syncDao().operation("account-a", operation.value))
            }
        }

    @Test
    fun `payload byte mutation after hash persistence is quarantined before receipt adoption`() =
        runTest {
            val operation = OperationId("operation-payload-mutated")
            val originalPayload = legacyPayload(operation, "원래 이름")
            val originalHash = exactLegacyHash(originalPayload)
            val mutatedPayload = originalPayload.replace("원래 이름", "변조 이름")
            enqueueRaw(operation, mutatedPayload, originalHash)
            val remote = FakeRemote(layout(4).copy(name = "변조 이름"))
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = originalHash

            val loaded =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(loaded.pending)

            assertEquals(MiniHomeSaveFailure.PAYLOAD_MISMATCH, pending.failure)
            assertTrue(pending.reconciliationDetails?.recomputedPayloadHash != originalHash)
            assertNotNull(database.syncDao().operation("account-a", operation.value))
        }

    @Test
    fun `raw legacy outbox adopts matching receipt without rewriting its payload hash`() = runTest {
        val operation = OperationId("operation-legacy-unicode")
        val rawName = "e\u0301".repeat(100)
        val payload = legacyPayload(operation, rawName)
        val exactHash = exactLegacyHash(payload)
        enqueueRaw(operation, payload, exactHash)
        val remote = FakeRemote(layout(4).copy(name = "é".repeat(100)))
        remote.committedOperationId = operation
        remote.committedExpectedRevision = Revision(3)
        remote.committedPayloadHash = exactHash
        remote.failLoad = true

        val offline =
            FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
        assertEquals(MiniHomePendingState.RECONCILIATION_REQUIRED, offline.pending?.state)
        assertEquals(
            "RECONCILIATION_REQUIRED",
            database.syncDao().operation("account-a", operation.value)?.state,
        )

        remote.failLoad = false
        val loaded = FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

        assertEquals("é".repeat(100), loaded.committed.name)
        assertNull(loaded.pending)
        assertNull(database.syncDao().operation("account-a", operation.value))
    }

    @Test
    fun `raw legacy outbox with different receipt hash remains typed reconciliation required`() =
        runTest {
            val operation = OperationId("operation-legacy-different")
            val payload = legacyPayload(operation, "e\u0301".repeat(100))
            val exactHash = exactLegacyHash(payload)
            enqueueRaw(operation, payload, exactHash)
            val remote = FakeRemote(layout(4).copy(name = "é".repeat(100)))
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = "f".repeat(64)

            val loaded =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
            val retained =
                requireNotNull(database.syncDao().operation("account-a", operation.value))

            assertEquals(MiniHomePendingState.RECONCILIATION_REQUIRED, loaded.pending?.state)
            assertEquals(MiniHomeSaveFailure.PAYLOAD_MISMATCH, loaded.pending?.failure)
            assertEquals("é".repeat(100), loaded.pending?.layout?.name)
            assertEquals(exactHash, retained.payloadHash)
            assertEquals("RECONCILIATION_REQUIRED", retained.state)
        }

    @Test
    fun `offline restart exposes canonicalized raw envelope and allows lineage discard`() =
        runTest {
            val operation = OperationId("operation-legacy-offline")
            val payload = legacyPayload(operation, "e\u0301".repeat(100))
            val exactHash = exactLegacyHash(payload)
            enqueueRaw(operation, payload, exactHash)
            val remote = FakeRemote(layout(3)).apply { failLoad = true }
            val repository = FirebaseMiniHomeRepository(database, remote)

            val loaded = repository.load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(loaded.pending)

            assertEquals("é".repeat(100), pending.layout.name)
            assertEquals(MiniHomePendingState.RECONCILIATION_REQUIRED, pending.state)
            assertEquals(MiniHomeSaveFailure.OUTBOX_MISMATCH, pending.failure)
            assertEquals(
                exactHash,
                database.syncDao().operation("account-a", operation.value)?.payloadHash,
            )

            assertEquals(
                MiniHomeDiscardResult.Consumed,
                repository.abandon(requireNotNull(pending.discardHandle)),
            )
            assertNull(database.syncDao().operation("account-a", operation.value))
        }

    @Test
    fun `malformed and canonical invalid envelopes remain observable discardable and account isolated`() =
        runTest {
            val malformed = OperationId("operation-malformed-a")
            val invalid = OperationId("operation-invalid-b")
            enqueueRaw(malformed, "{not-json", "a".repeat(64), accountId = "account-a")
            val invalidPayload = legacyPayload(invalid, "A\u202EB", owner = "account-b")
            enqueueRaw(
                invalid,
                invalidPayload,
                exactLegacyHash(invalidPayload),
                accountId = "account-b",
            )
            val remote = FakeRemote(layout(3)).apply { failLoad = true }
            val repository = FirebaseMiniHomeRepository(database, remote)

            val accountA = repository.load() as MiniHomeLoadResult.Ready
            val pendingA = requireNotNull(accountA.pending)
            assertEquals(malformed, pendingA.operationId)
            assertEquals(MiniHomeSaveFailure.MALFORMED_RESPONSE, pendingA.failure)
            assertEquals(MiniHomePendingState.RECONCILIATION_REQUIRED, pendingA.state)
            assertEquals(
                MiniHomeDiscardResult.Consumed,
                repository.abandon(requireNotNull(pendingA.discardHandle)),
            )
            assertNull(database.syncDao().operation("account-a", malformed.value))
            assertNotNull(database.syncDao().operation("account-b", invalid.value))

            remote.account = AccountId("account-b")
            val accountB = repository.load() as MiniHomeLoadResult.Ready
            val pendingB = requireNotNull(accountB.pending)
            assertEquals(invalid, pendingB.operationId)
            assertEquals(MiniHomeSaveFailure.MALFORMED_RESPONSE, pendingB.failure)
            assertEquals(
                MiniHomeDiscardResult.Consumed,
                repository.abandon(requireNotNull(pendingB.discardHandle)),
            )
            assertNull(database.syncDao().operation("account-b", invalid.value))
        }

    @Test
    fun `raw hash mismatch survives repeated restart with exact local and authoritative details`() =
        runTest {
            val operation = OperationId("operation-raw-observable")
            val payload = legacyPayload(operation, "e\u0301".repeat(100))
            val localHash = exactLegacyHash(payload)
            val authoritativeHash = "f".repeat(64)
            enqueueRaw(operation, payload, localHash)
            val remote = FakeRemote(layout(4).copy(name = "é".repeat(100)))
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = authoritativeHash

            repeat(2) {
                val loaded =
                    FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready
                val pending = requireNotNull(loaded.pending)
                val details = requireNotNull(pending.reconciliationDetails)

                assertEquals(MiniHomePendingState.RECONCILIATION_REQUIRED, pending.state)
                assertEquals(MiniHomeSaveFailure.PAYLOAD_MISMATCH, pending.failure)
                assertEquals(payload, details.rawEnvelopeJson)
                assertEquals(operation.value, details.rowOperationId)
                assertEquals(operation.value, details.envelopeOperationId)
                assertEquals("e\u0301".repeat(100), details.rawName)
                assertEquals(localHash, details.storedPayloadHash)
                assertEquals(operation.value, details.authoritativeOperationId)
                assertEquals(3L, details.authoritativeExpectedRevision)
                assertEquals(4L, details.authoritativeRevision)
                assertEquals(authoritativeHash, details.authoritativePayloadHash)
                assertNotNull(database.syncDao().operation("account-a", operation.value))
                if (it == 0) remote.failLoad = true
            }
        }

    @Test
    fun `malformed row identity exposes true discard handle and atomically removes only its persisted lineage`() =
        runTest {
            val malformedRowOperation = "malformed/row-operation"
            val malformedRowLineage = "malformed/row-lineage"
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        malformedRowOperation,
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        3,
                        "{not-json",
                        2,
                        payloadHash = "a".repeat(64),
                        lineageId = malformedRowLineage,
                    )
                )
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "related-valid-operation",
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        3,
                        "{also-not-json",
                        1,
                        payloadHash = "b".repeat(64),
                        lineageId = malformedRowLineage,
                    )
                )
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        malformedRowOperation,
                        "account-b",
                        "miniHomeLayouts",
                        "home-b",
                        "REPLACE",
                        3,
                        "{foreign-json",
                        3,
                        payloadHash = "c".repeat(64),
                        lineageId = malformedRowLineage,
                    )
                )
            val remote = FakeRemote(layout(3)).apply { failLoad = true }
            val repository = FirebaseMiniHomeRepository(database, remote)

            val first = repository.load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(first.pending)
            val handle = requireNotNull(pending.discardHandle)
            assertEquals(MiniHomeSaveFailure.MALFORMED_RESPONSE, pending.failure)
            assertEquals("account-a", handle.accountId.value)
            assertEquals("miniHomeLayouts", handle.aggregateType)
            assertEquals(malformedRowOperation, handle.rowOperationId)
            assertEquals(malformedRowLineage, handle.rowLineageId)
            assertEquals(malformedRowOperation, pending.reconciliationDetails?.rowOperationId)
            assertEquals("{not-json", pending.reconciliationDetails?.rawEnvelopeJson)

            val restartedRepository = FirebaseMiniHomeRepository(database, remote)
            val restartedPending =
                requireNotNull((restartedRepository.load() as MiniHomeLoadResult.Ready).pending)
            assertEquals(handle, restartedPending.discardHandle)
            restartedRepository.abandon(requireNotNull(restartedPending.discardHandle))

            assertNull(database.syncDao().operation("account-a", malformedRowOperation))
            assertNull(database.syncDao().operation("account-a", "related-valid-operation"))
            assertNotNull(database.syncDao().operation("account-b", malformedRowOperation))
            val restarted = FirebaseMiniHomeRepository(database, remote).load()
            assertNull((restarted as MiniHomeLoadResult.Ready).pending)
        }

    @Test
    fun `decoded payload identity mismatch discards by persisted row lineage not envelope lineage`() =
        runTest {
            val envelopeOperation = OperationId("envelope-operation")
            val rowOperation = "persisted-row-operation"
            val rowLineage = "persisted-row-lineage"
            val payloadRoot =
                Json.parseToJsonElement(legacyPayload(envelopeOperation, "안전한 이름")).jsonObject
            val mismatchedPayload =
                JsonObject(
                        payloadRoot +
                            ("lineageId" to JsonPrimitive("envelope-lineage")) +
                            ("operationId" to JsonPrimitive(envelopeOperation.value))
                    )
                    .toString()
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        rowOperation,
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        3,
                        mismatchedPayload,
                        1,
                        payloadHash = "d".repeat(64),
                        lineageId = rowLineage,
                    )
                )
            val remote = FakeRemote(layout(3)).apply { failLoad = true }
            val repository = FirebaseMiniHomeRepository(database, remote)

            val loaded = repository.load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(loaded.pending)
            val handle = requireNotNull(pending.discardHandle)
            assertEquals(rowOperation, handle.rowOperationId)
            assertEquals(rowLineage, handle.rowLineageId)
            assertEquals(
                envelopeOperation.value,
                pending.reconciliationDetails?.envelopeOperationId,
            )

            repository.abandon(handle)

            assertNull(database.syncDao().operation("account-a", rowOperation))
            assertNull((repository.load() as MiniHomeLoadResult.Ready).pending)
        }

    @Test
    fun `explicit reconciliation of malformed envelope consumes the true durable handle`() =
        runTest {
            val rowOperation = OperationId("malformed-reconcile-row")
            enqueueRaw(rowOperation, "{not-json", "e".repeat(64))
            val remote = FakeRemote(layout(3))
            val repository = FirebaseMiniHomeRepository(database, remote)
            val loaded = repository.load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(loaded.pending)
            val request =
                MiniHomeSaveRequest(
                    loaded.accountId,
                    pending.operationId,
                    pending.expectedRevision,
                    pending.layout,
                    pending.lineageId,
                    pending.supersedesOperationId,
                )

            val result =
                repository.reconcile(
                    request,
                    MiniHomeSaveFailure.MALFORMED_RESPONSE,
                    requireNotNull(pending.discardHandle),
                )

            assertTrue(result is MiniHomeSaveResult.Reconciled)
            assertNull(database.syncDao().operation("account-a", rowOperation.value))
            assertNull(
                (FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready)
                    .pending
            )
        }

    @Test
    fun `foreign active account cannot use another owners discard handle`() = runTest {
        val operation = OperationId("operation-owner-handle")
        enqueueRaw(operation, "{not-json", "e".repeat(64))
        val remote = FakeRemote(layout(3)).apply { failLoad = true }
        val repository = FirebaseMiniHomeRepository(database, remote)
        val loaded = repository.load() as MiniHomeLoadResult.Ready
        val handle = requireNotNull(loaded.pending?.discardHandle)

        remote.account = AccountId("account-b")
        repository.abandon(handle)

        assertNotNull(database.syncDao().operation("account-a", operation.value))
    }

    @Test
    fun `stale discard handle cannot delete replacement row with same operation and lineage`() =
        runTest {
            val operation = OperationId("operation-stale-handle")
            enqueueRaw(operation, "{not-json", "a".repeat(64))
            val remote = FakeRemote(layout(3)).apply { failLoad = true }
            val repository = FirebaseMiniHomeRepository(database, remote)
            val loaded = repository.load() as MiniHomeLoadResult.Ready
            val staleHandle = requireNotNull(loaded.pending?.discardHandle)
            val original =
                requireNotNull(database.syncDao().operation("account-a", operation.value))
            database.syncDao().remove("account-a", operation.value)
            database.syncDao().enqueue(original.copy(rowHandleId = "replacement-generation"))

            val result = repository.abandon(staleHandle)

            assertTrue(result is MiniHomeDiscardResult.StaleHandle)
            val replacement =
                requireNotNull(database.syncDao().operation("account-a", operation.value))
            assertEquals("replacement-generation", replacement.rowHandleId)
        }

    @Test
    fun `row replacement during handle reconciliation fails closed without retiring ABA row`() =
        runTest {
            val operation = OperationId("operation-reconcile-aba")
            enqueueRaw(operation, "{not-json", "a".repeat(64))
            val remote = FakeRemote(layout(3))
            val repository = FirebaseMiniHomeRepository(database, remote)
            val loaded = repository.load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(loaded.pending)
            val handle = requireNotNull(pending.discardHandle)
            val request =
                MiniHomeSaveRequest(
                    loaded.accountId,
                    pending.operationId,
                    pending.expectedRevision,
                    pending.layout,
                    pending.lineageId,
                    pending.supersedesOperationId,
                )
            remote.onLoad = {
                val current =
                    requireNotNull(database.syncDao().operation("account-a", operation.value))
                database.syncDao().remove("account-a", operation.value)
                database.syncDao().enqueue(current.copy(rowHandleId = "aba-replacement-generation"))
                remote.onLoad = null
            }

            val result =
                repository.reconcile(
                    request,
                    MiniHomeSaveFailure.MALFORMED_RESPONSE,
                    handle,
                )

            assertTrue(result is MiniHomeSaveResult.PendingChanged)
            assertEquals(
                "aba-replacement-generation",
                database.syncDao().operation("account-a", operation.value)?.rowHandleId,
            )
        }

    @Test
    fun `in flight reconciliation linearizes before discard without stale mutation`() = runTest {
        val operation = OperationId("operation-reconcile-discard-race")
        enqueueRaw(operation, "{not-json", "a".repeat(64))
        val remote = FakeRemote(layout(3))
        val repository = FirebaseMiniHomeRepository(database, remote)
        val loaded = repository.load() as MiniHomeLoadResult.Ready
        val pending = requireNotNull(loaded.pending)
        val handle = requireNotNull(pending.discardHandle)
        val request =
            MiniHomeSaveRequest(
                loaded.accountId,
                pending.operationId,
                pending.expectedRevision,
                pending.layout,
                pending.lineageId,
                pending.supersedesOperationId,
            )
        val loadEntered = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        remote.onLoad = {
            loadEntered.complete(Unit)
            releaseLoad.await()
            remote.onLoad = null
        }

        val reconciling = async {
            repository.reconcile(
                request,
                MiniHomeSaveFailure.MALFORMED_RESPONSE,
                handle,
            )
        }
        loadEntered.await()
        val discarding = async { repository.abandon(handle) }
        yield()
        assertFalse(discarding.isCompleted)
        releaseLoad.complete(Unit)

        val result = reconciling.await()
        assertTrue(result is MiniHomeSaveResult.Reconciled)
        assertEquals(MiniHomeDiscardResult.Missing, discarding.await())
        assertNull(database.syncDao().operation("account-a", operation.value))
    }

    @Test
    fun `wrong owner type and row generation handles fail closed before authoritative load`() =
        runTest {
            val operation = OperationId("operation-wrong-handle")
            enqueueRaw(operation, "{not-json", "a".repeat(64))
            val remote = FakeRemote(layout(3)).apply { failLoad = true }
            val repository = FirebaseMiniHomeRepository(database, remote)
            val loaded = repository.load() as MiniHomeLoadResult.Ready
            val pending = requireNotNull(loaded.pending)
            val real = requireNotNull(pending.discardHandle)
            val request =
                MiniHomeSaveRequest(
                    loaded.accountId,
                    pending.operationId,
                    pending.expectedRevision,
                    pending.layout,
                    pending.lineageId,
                    pending.supersedesOperationId,
                )
            val beforeLoads = remote.loadCalls

            listOf(
                    real.copy(accountId = AccountId("account-b")),
                    real.copy(aggregateType = "personalPlants"),
                    real.copy(rowHandleId = "wrong-generation"),
                )
                .forEach { wrong ->
                    assertTrue(
                        repository.reconcile(
                            request,
                            MiniHomeSaveFailure.MALFORMED_RESPONSE,
                            wrong,
                        ) is MiniHomeSaveResult.PendingChanged
                    )
                    repository.abandon(wrong)
                    assertNotNull(database.syncDao().operation("account-a", operation.value))
                }

            assertEquals(beforeLoads, remote.loadCalls)
        }

    @Test
    fun `every remote save outcome CAS discards stale result without mutating ABA replacement`() =
        runTest {
            val outcomes =
                listOf(
                    RemoteMiniHomeSaveResult.Applied(Revision(4)),
                    RemoteMiniHomeSaveResult.Duplicate(Revision(4)),
                    RemoteMiniHomeSaveResult.Conflict(Revision(5)),
                    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK, "network"),
                    RemoteMiniHomeSaveResult.Failed(
                        MiniHomeSaveFailure.PAYLOAD_MISMATCH,
                        "payload",
                        committedOperationId = OperationId("stale-receipt"),
                        committedExpectedRevision = Revision(3),
                        committedRevision = Revision(4),
                        committedPayloadHash = "e".repeat(64),
                    ),
                )
            outcomes.forEachIndexed { index, outcome ->
                val operation = OperationId("operation-save-result-aba-$index")
                val remote = FakeRemote(layout(3), outcome)
                val repository = FirebaseMiniHomeRepository(database, remote)
                val request = request(operation.value, layout(3).copy(name = "전송한 편집 $index"))
                lateinit var replacement: OperationOutboxEntity
                remote.onSave = {
                    val current =
                        requireNotNull(database.syncDao().operation("account-a", operation.value))
                    database.syncDao().remove("account-a", operation.value)
                    replacement =
                        current.copy(
                            state = "RECONCILIATION_REQUIRED",
                            lastErrorCode = "PAYLOAD_MISMATCH",
                            failureDetails = "replacement details $index",
                            committedOperationId = "replacement-receipt-$index",
                            committedExpectedRevision = 8,
                            committedRevision = 9,
                            committedPayloadHash = "f".repeat(64),
                            rowHandleId = "save-aba-generation-2-$index",
                            rowVersion = 0,
                        )
                    database.syncDao().enqueue(replacement)
                    remote.onSave = null
                }

                val result = repository.save(request)

                assertTrue("outcome=$outcome", result is MiniHomeSaveResult.PendingChanged)
                assertEquals(
                    "outcome=$outcome",
                    replacement,
                    database.syncDao().operation("account-a", operation.value),
                )
            }
        }

    @Test
    fun `load payload hash backfill CAS cannot mutate row inserted during remote await`() =
        runTest {
            val operation = OperationId("operation-load-hash-aba")
            enqueue(operation, layout(3).copy(name = "원래 편집"))
            val original =
                requireNotNull(database.syncDao().operation("account-a", operation.value))
            database.syncDao().remove("account-a", operation.value)
            database
                .syncDao()
                .enqueue(original.copy(payloadHash = null, rowHandleId = "load-generation-1"))
            val remote = FakeRemote(layout(3))
            lateinit var replacement: OperationOutboxEntity
            remote.onLoad = {
                val current =
                    requireNotNull(database.syncDao().operation("account-a", operation.value))
                database.syncDao().remove("account-a", operation.value)
                replacement =
                    current.copy(
                        payloadHash = "f".repeat(64),
                        state = "RECONCILIATION_REQUIRED",
                        lastErrorCode = "PAYLOAD_MISMATCH",
                        failureDetails = "replacement hash is authoritative local state",
                        rowHandleId = "load-generation-2",
                        rowVersion = 0,
                    )
                database.syncDao().enqueue(replacement)
                remote.onLoad = null
            }

            val loaded =
                FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

            assertEquals("load-generation-2", loaded.pending?.discardHandle?.rowHandleId)
            assertEquals(replacement, database.syncDao().operation("account-a", operation.value))
        }

    @Test
    fun `post outbox discard waits for the registered save critical section`() = runTest {
        val remote = FakeRemote(layout(3))
        val saveEntered = CompletableDeferred<Unit>()
        val saveGate = CompletableDeferred<Unit>()
        remote.onSave = {
            saveEntered.complete(Unit)
            saveGate.await()
        }
        val repository = FirebaseMiniHomeRepository(database, remote)
        val request = request("operation-post-outbox-race", layout(3).copy(name = "확정 경합 편집"))
        val saving = async { repository.save(request) }
        saveEntered.await()
        assertNotNull(database.syncDao().operation("account-a", request.operationId.value))

        val discarding = async { repository.abandonPending(AccountId("account-a")) }
        yield()

        assertFalse(discarding.isCompleted)
        saveGate.complete(Unit)
        assertTrue(saving.await() is MiniHomeSaveResult.Saved)
        assertEquals(MiniHomeDiscardResult.Missing, discarding.await())
        assertNull(database.syncDao().operation("account-a", request.operationId.value))
    }

    @Test
    fun `activity cancellation after remote boundary finishes durable receipt reconciliation`() =
        runTest {
            val remote = FakeRemote(layout(3))
            val remoteEntered = CompletableDeferred<Unit>()
            val remoteGate = CompletableDeferred<Unit>()
            remote.onSave = {
                remoteEntered.complete(Unit)
                remoteGate.await()
            }
            val repository = FirebaseMiniHomeRepository(database, remote)
            val request =
                request("operation-activity-recreation", layout(3).copy(name = "재생성 중 저장"))
            val saving = async { repository.save(request) }
            remoteEntered.await()
            saving.cancel()
            yield()
            assertNotNull(database.syncDao().operation("account-a", request.operationId.value))

            remoteGate.complete(Unit)
            saving.join()

            assertNull(database.syncDao().operation("account-a", request.operationId.value))
            val recreatedDiscard =
                repository.abandonPending(AccountId("account-a"), request.operationId)
            recreatedDiscard as MiniHomeDiscardResult.Committed
            assertEquals("재생성 중 저장", recreatedDiscard.authoritative.name)
            val restored = repository.load() as MiniHomeLoadResult.Ready
            assertEquals("재생성 중 저장", restored.committed.name)
            assertNull(restored.pending)
        }

    @Test
    fun `response loss discard reconciles committed receipt instead of silently consuming uncertainty`() =
        runTest {
            val remote = CommitThenNetworkRemote(layout(3))
            val repository = FirebaseMiniHomeRepository(database, remote)
            val request =
                request("operation-response-loss-discard", layout(3).copy(name = "응답 유실 확정"))
            val failed = repository.save(request) as MiniHomeSaveResult.Failed
            assertEquals(MiniHomeSaveFailure.NETWORK, failed.failure)
            assertNotNull(failed.discardHandle)

            val result = repository.abandonPending(AccountId("account-a"))

            assertEquals(MiniHomeDiscardResult.Committed(remote.layout), result)
            assertNull(database.syncDao().operation("account-a", request.operationId.value))
            assertEquals(1, remote.saveCalls)
        }

    @Test
    fun `process restart reconciles response loss before honoring discard intent`() = runTest {
        val remote = CommitThenNetworkRemote(layout(3))
        val request = request("operation-restart-discard", layout(3).copy(name = "재시작 응답 유실"))
        val first = FirebaseMiniHomeRepository(database, remote)
        assertTrue(first.save(request) is MiniHomeSaveResult.Failed)

        val restarted = FirebaseMiniHomeRepository(database, remote)
        val result = restarted.abandonPending(AccountId("account-a"))

        assertEquals(MiniHomeDiscardResult.Committed(remote.layout), result)
        assertNull(database.syncDao().operation("account-a", request.operationId.value))
        assertEquals(1, remote.saveCalls)
    }

    @Test
    fun `pre outbox write failure resolves authoritative no row as missing`() = runTest {
        val remote = FakeRemote(layout(3))
        val repository = FirebaseMiniHomeRepository(database, remote)
        val request = request("operation-pre-outbox-failure", layout(3).copy(name = "로컬 실패 편집"))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TEMP TRIGGER fail_mini_home_outbox_insert " +
                "BEFORE INSERT ON operation_outbox BEGIN " +
                "SELECT RAISE(ABORT, 'forced pre-outbox failure'); END"
        )

        val failed = repository.save(request) as MiniHomeSaveResult.Failed

        assertEquals(MiniHomeSaveFailure.DATABASE, failed.failure)
        assertNull(failed.discardHandle)
        sqlite.execSQL("DROP TRIGGER fail_mini_home_outbox_insert")
        assertNull(database.syncDao().operation("account-a", request.operationId.value))
        assertEquals(
            MiniHomeDiscardResult.Missing,
            repository.abandonPending(AccountId("account-a")),
        )
        assertNull(database.syncDao().operation("account-a", request.operationId.value))
    }

    @Test
    fun `handleless discard distinguishes authoritative pending query failure from no row`() =
        runTest {
            val repository = FirebaseMiniHomeRepository(database, FakeRemote(layout(3)))
            database.openHelper.writableDatabase.execSQL("DROP TABLE operation_outbox")

            assertEquals(
                MiniHomeDiscardResult.Rejected,
                repository.abandonPending(AccountId("account-a")),
            )
        }

    @Test
    fun `handleless discard finds and CAS consumes a row that exists before authoritative query`() =
        runTest {
            val remote =
                FakeRemote(
                    layout(3),
                    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK),
                )
            val repository = FirebaseMiniHomeRepository(database, remote)
            val request = request("operation-handleless-found", layout(3).copy(name = "나중 행"))
            val failed = repository.save(request) as MiniHomeSaveResult.Failed
            assertNotNull(failed.discardHandle)

            val result = repository.abandonPending(AccountId("account-a"))

            assertEquals(MiniHomeDiscardResult.Consumed, result)
            assertNull(database.syncDao().operation("account-a", request.operationId.value))
        }

    @Test
    fun `failed save keeps committed cache and durable exact pending draft`() = runTest {
        val remote =
            FakeRemote(layout(3), RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK))
        val repository = FirebaseMiniHomeRepository(database, remote)
        repository.load()
        val draft = layout(3).copy(name = "편집한 방")
        val request =
            MiniHomeSaveRequest(
                AccountId("account-a"),
                OperationId("operation-layout-1"),
                Revision(3),
                draft,
            )

        val failed = repository.save(request) as MiniHomeSaveResult.Failed
        val responseHandle = requireNotNull(failed.discardHandle)
        assertEquals(MiniHomeSaveFailure.NETWORK, failed.failure)
        remote.failLoad = true
        val restored = repository.load() as MiniHomeLoadResult.Ready

        assertEquals("저장된 방", restored.committed.name)
        assertEquals(draft, restored.pending?.layout)
        assertEquals(MiniHomePendingState.MAY_HAVE_COMMITTED, restored.pending?.state)
        assertEquals(responseHandle, restored.pending?.discardHandle)

        val restarted = FirebaseMiniHomeRepository(database, remote)
        assertEquals(MiniHomeDiscardResult.Rejected, restarted.abandon(responseHandle))
        assertNotNull(database.syncDao().operation("account-a", "operation-layout-1"))
        remote.failLoad = false
        assertEquals(MiniHomeDiscardResult.Consumed, restarted.abandon(responseHandle))
        assertNull(database.syncDao().operation("account-a", "operation-layout-1"))
    }

    @Test
    fun `restart after response loss adopts the matching committed operation without resend`() =
        runTest {
            val remote = ResponseLossRemote(layout(3))
            val repository = FirebaseMiniHomeRepository(database, remote)
            val draft = layout(3).copy(name = "응답 유실 편집")
            val request = request("operation-response-loss", draft)

            val failed = repository.save(request) as MiniHomeSaveResult.Failed
            assertEquals(MiniHomeSaveFailure.INCONSISTENT_RECEIPT, failed.failure)
            assertNotNull(failed.discardHandle)
            val uncertain =
                requireNotNull(database.syncDao().operation("account-a", "operation-response-loss"))
            assertEquals("MAY_HAVE_COMMITTED", uncertain.state)
            assertEquals("INCONSISTENT_RECEIPT", uncertain.lastErrorCode)
            assertEquals("operation-response-loss", uncertain.committedOperationId)
            assertEquals(3L, uncertain.committedExpectedRevision)
            assertEquals(4L, uncertain.committedRevision)

            val restarted = FirebaseMiniHomeRepository(database, remote)
            val restored = restarted.load() as MiniHomeLoadResult.Ready

            assertEquals(remote.layout, restored.committed)
            assertNull(restored.pending)
            assertEquals(
                MiniHomeCommittedReceipt(
                    request.operationId,
                    request.expectedRevision,
                    remote.layout.revision,
                    MiniHomePayloadHash.create(request.expectedRevision, request.layout),
                ),
                restored.committedReceipt,
            )
            assertEquals(listOf("operation-response-loss"), remote.operations)
            assertNull(database.syncDao().operation("account-a", "operation-response-loss"))
        }

    @Test
    fun `restart reconciles a transport-lost commit before offering any retry`() = runTest {
        val remote = CommitThenNetworkRemote(layout(3))
        val draft = layout(3).copy(name = "전송 응답 유실")
        val request = request("operation-network-loss", draft)

        val failed =
            FirebaseMiniHomeRepository(database, remote).save(request) as MiniHomeSaveResult.Failed
        assertEquals(MiniHomeSaveFailure.NETWORK, failed.failure)
        assertNotNull(failed.discardHandle)
        assertEquals(
            "MAY_HAVE_COMMITTED",
            database.syncDao().operation("account-a", "operation-network-loss")?.state,
        )

        val restored =
            FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

        assertEquals(Revision(4), restored.committed.revision)
        assertEquals("전송 응답 유실", restored.committed.name)
        assertNull(restored.pending)
        assertEquals(request.operationId, restored.committedReceipt?.operationId)
        assertEquals(request.expectedRevision, restored.committedReceipt?.expectedRevision)
        assertEquals(restored.committed.revision, restored.committedReceipt?.committedRevision)
        assertEquals(
            MiniHomePayloadHash.create(request.expectedRevision, request.layout),
            restored.committedReceipt?.payloadHash,
        )
        assertEquals(1, remote.saveCalls)
    }

    @Test
    fun `unavailable plant and decor are removed while valid edits and name are preserved`() =
        runTest {
            val remote =
                FakeRemote(
                    layout(3),
                    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
                )
            remote.plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-b"), "스투키", null))
            remote.decorations = emptyList()
            val repository = FirebaseMiniHomeRepository(database, remote)
            val placements =
                MiniHomePlacementPolicy.layer(
                    listOf(
                        placement("removed-plant", "plant-a", GridPosition(0, 0)),
                        placement("valid-plant", "plant-b", GridPosition(1, 1)),
                        decoration("removed-decor", "decor-a", GridPosition(2, 2)),
                    )
                )
            val draft = layout(3).copy(name = "보존할 이름", placements = placements)

            val fixedRequest = request("operation-unavailable", draft)
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
                repository.save(fixedRequest),
            )
            assertEquals(1, remote.savedRequests.size)
            assertNotNull(database.syncDao().operation("account-a", "operation-unavailable"))
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
                repository.save(fixedRequest),
            )
            assertEquals(1, remote.savedRequests.size)

            val reconciled =
                reconcileCurrent(repository, fixedRequest, MiniHomeSaveFailure.UNAVAILABLE_ENTITY)
                    as MiniHomeSaveResult.Reconciled
            assertEquals(MiniHomeSaveFailure.UNAVAILABLE_ENTITY, reconciled.failure)
            assertEquals("보존할 이름", reconciled.correctedDraft.name)
            assertEquals(
                listOf("valid-plant"),
                reconciled.correctedDraft.placements.map { it.id.value },
            )
            assertEquals(GridPosition(1, 1), reconciled.correctedDraft.placements.single().position)
            assertEquals(2, reconciled.removedTargets)
            assertNull(database.syncDao().operation("account-a", "operation-unavailable"))
        }

    @Test
    fun `server mismatch receipt reconciles committed remote operation without repeating request`() =
        runTest {
            val operation = OperationId("operation-server-mismatch")
            val remote =
                FakeRemote(
                    layout(3).copy(name = "서버에 먼저 확정된 편집", revision = Revision(4)),
                    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.OUTBOX_MISMATCH),
                )
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = MiniHomePayloadHash.create(Revision(3), remote.layout)
            val repository = FirebaseMiniHomeRepository(database, remote)
            val currentDraft = layout(3).copy(name = "현재 기기 편집")
            val fixedRequest = request(operation.value, currentDraft)

            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.OUTBOX_MISMATCH),
                repository.save(fixedRequest),
            )
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.OUTBOX_MISMATCH),
                repository.save(fixedRequest),
            )

            val reconciled =
                reconcileCurrent(repository, fixedRequest, MiniHomeSaveFailure.OUTBOX_MISMATCH)
                    as MiniHomeSaveResult.Reconciled
            assertEquals("현재 기기 편집", reconciled.correctedDraft.name)
            assertEquals(Revision(4), reconciled.correctedDraft.revision)
            assertEquals(1, remote.savedRequests.size)
            assertNull(database.syncDao().operation("account-a", operation.value))
        }

    @Test
    fun `mismatch after restart adopts committed old outbox then safely rebases current draft`() =
        runTest {
            val operation = OperationId("operation-mismatch-restart")
            val oldDraft = layout(3).copy(name = "먼저 보낸 편집")
            enqueue(operation, oldDraft)
            val remote = FakeRemote(oldDraft.copy(revision = Revision(4)))
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = MiniHomePayloadHash.create(Revision(3), oldDraft)
            val repository = FirebaseMiniHomeRepository(database, remote)
            val currentDraft = layout(3).copy(name = "재시작 뒤 편집")

            val fixedRequest = request(operation.value, currentDraft)
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.OUTBOX_MISMATCH),
                repository.save(fixedRequest),
            )
            assertTrue(remote.savedRequests.isEmpty())
            assertNotNull(database.syncDao().operation("account-a", operation.value))

            val reconciled =
                reconcileCurrent(repository, fixedRequest, MiniHomeSaveFailure.OUTBOX_MISMATCH)
                    as MiniHomeSaveResult.Reconciled
            assertEquals(MiniHomeSaveFailure.OUTBOX_MISMATCH, reconciled.failure)
            assertEquals("재시작 뒤 편집", reconciled.correctedDraft.name)
            assertEquals(Revision(4), reconciled.correctedDraft.revision)
            assertNull(database.syncDao().operation("account-a", operation.value))
            assertTrue(remote.savedRequests.isEmpty())
        }

    @Test
    fun `unavailable response plus inventory-changing revision becomes typed conflict`() = runTest {
        val remote =
            FakeRemote(
                layout(4),
                RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
            )
        remote.plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-b"), "새 식물", null))
        val repository = FirebaseMiniHomeRepository(database, remote)

        val request = request("operation-unavailable-conflict", layout(3).copy(name = "내 편집"))
        assertEquals(
            MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
            repository.save(request),
        )
        assertEquals(1, remote.savedRequests.size)

        val reconciled =
            reconcileCurrent(repository, request, MiniHomeSaveFailure.UNAVAILABLE_ENTITY)
                as MiniHomeSaveResult.Reconciled
        assertEquals(Revision(4), reconciled.authoritative.revision)
        assertEquals(listOf(PersonalPlantId("plant-b")), reconciled.plants.map { it.id })
        assertNull(database.syncDao().operation("account-a", "operation-unavailable-conflict"))
    }

    @Test
    fun `mismatch against another committed revision blocks save and rebases only on explicit reconciliation`() =
        runTest {
            val operation = OperationId("operation-unsafe-mismatch")
            enqueue(operation, layout(3).copy(name = "기존 outbox"))
            val remote = FakeRemote(layout(4))
            remote.committedOperationId = OperationId("operation-other-commit")
            remote.plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-b"), "새 식물", null))
            val repository = FirebaseMiniHomeRepository(database, remote)

            val fixedRequest = request(operation.value, layout(3).copy(name = "다른 fixed request"))
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.OUTBOX_MISMATCH),
                repository.save(fixedRequest),
            )

            val blocked = requireNotNull(database.syncDao().operation("account-a", operation.value))
            assertEquals("RECONCILIATION_REQUIRED", blocked.state)
            assertEquals("OUTBOX_MISMATCH", blocked.lastErrorCode)
            assertTrue(remote.savedRequests.isEmpty())

            val restarted = FirebaseMiniHomeRepository(database, remote)
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.OUTBOX_MISMATCH),
                restarted.save(fixedRequest),
            )
            assertTrue(remote.savedRequests.isEmpty())
            assertNotNull(database.syncDao().operation("account-a", operation.value))

            val reconciled =
                reconcileCurrent(restarted, fixedRequest, MiniHomeSaveFailure.OUTBOX_MISMATCH)
                    as MiniHomeSaveResult.Reconciled
            assertEquals(Revision(4), reconciled.authoritative.revision)
            assertEquals(listOf(PersonalPlantId("plant-b")), reconciled.plants.map { it.id })
            assertNull(database.syncDao().operation("account-a", operation.value))
            assertTrue(remote.savedRequests.isEmpty())
        }

    @Test
    fun `failed reconciliation persists typed unavailable state across restart without retry`() =
        runTest {
            val remote =
                FakeRemote(
                    layout(3),
                    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
                )
            remote.failLoad = true
            val repository = FirebaseMiniHomeRepository(database, remote)
            val request = request("operation-unavailable-offline", layout(3).copy(name = "편집"))

            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.UNAVAILABLE_ENTITY),
                repository.save(request),
            )
            remote.failLoad = false

            val restored = repository.load() as MiniHomeLoadResult.Ready
            assertEquals(
                MiniHomePendingState.RECONCILIATION_REQUIRED,
                restored.pending?.state,
            )
            assertEquals(MiniHomeSaveFailure.UNAVAILABLE_ENTITY, restored.pending?.failure)
            assertEquals("UNAVAILABLE_ENTITY", restored.pending?.failureDetails)
            assertEquals(1, remote.savedRequests.size)
        }

    @Test
    fun `persisted transient reasons alone retry the exact frozen request`() = runTest {
        val transient =
            listOf(
                MiniHomeSaveFailure.NETWORK,
                MiniHomeSaveFailure.DATABASE,
                MiniHomeSaveFailure.INCONSISTENT_RECEIPT,
            )
        val remote = FakeRemote(layout(3))
        val repository = FirebaseMiniHomeRepository(database, remote)

        transient.forEachIndexed { index, reason ->
            remote.layout = layout(3)
            remote.committedOperationId = null
            remote.committedExpectedRevision = null
            remote.committedPayloadHash = null
            val operation = OperationId("operation-transient-${index + 1}")
            val draft = layout(3).copy(name = "${reason.name} retry")
            enqueue(operation, draft)
            database
                .syncDao()
                .markMayHaveCommitted("account-a", operation.value, reason.name, reason.name)

            val result = repository.save(request(operation.value, draft))

            assertTrue(result is MiniHomeSaveResult.Saved)
            assertNull(database.syncDao().operation("account-a", operation.value))
        }
        assertEquals(transient.size, remote.savedRequests.size)
        assertEquals(
            transient.size,
            remote.savedRequests.map { it.operationId }.distinct().size,
        )
    }

    @Test
    fun `same remote operation with different payload hash at higher revision stays blocked until explicit reconciliation`() =
        runTest {
            val operation = OperationId("operation-payload-mismatch")
            val localDraft = layout(3).copy(name = "보존할 로컬 편집")
            enqueue(operation, localDraft)
            database
                .syncDao()
                .markMayHaveCommitted(
                    "account-a",
                    operation.value,
                    MiniHomeSaveFailure.INCONSISTENT_RECEIPT.name,
                    "response lost",
                )
            val authoritative = layout(5).copy(name = "다른 payload로 확정됨")
            val remote = FakeRemote(authoritative)
            remote.committedOperationId = operation
            remote.committedExpectedRevision = Revision(3)
            remote.committedPayloadHash = MiniHomePayloadHash.create(Revision(3), authoritative)
            val repository = FirebaseMiniHomeRepository(database, remote)

            val restored = repository.load() as MiniHomeLoadResult.Ready

            assertEquals(MiniHomeSaveFailure.PAYLOAD_MISMATCH, restored.pending?.failure)
            val blocked = requireNotNull(database.syncDao().operation("account-a", operation.value))
            assertEquals("RECONCILIATION_REQUIRED", blocked.state)
            assertEquals("PAYLOAD_MISMATCH", blocked.lastErrorCode)
            assertEquals(5L, blocked.committedRevision)
            assertEquals(remote.committedPayloadHash, blocked.committedPayloadHash)
            assertTrue(blocked.failureDetails.orEmpty().contains(blocked.payloadHash.orEmpty()))
            assertTrue(
                blocked.failureDetails.orEmpty().contains(blocked.committedPayloadHash.orEmpty())
            )

            val fixedRequest = request(operation.value, localDraft)
            assertEquals(
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.PAYLOAD_MISMATCH),
                repository.save(fixedRequest),
            )
            assertTrue(remote.savedRequests.isEmpty())
            assertNotNull(database.syncDao().operation("account-a", operation.value))

            val corrected =
                reconcileCurrent(repository, fixedRequest, MiniHomeSaveFailure.PAYLOAD_MISMATCH)
                    as MiniHomeSaveResult.Reconciled
            assertEquals("보존할 로컬 편집", corrected.correctedDraft.name)
            assertEquals(Revision(5), corrected.correctedDraft.revision)
            assertNull(database.syncDao().operation("account-a", operation.value))
            assertTrue(remote.savedRequests.isEmpty())
        }

    @Test
    fun `all persisted permanent reasons survive restart and save tap without callable transmission`() =
        runTest {
            val permanent =
                listOf(
                    MiniHomeSaveFailure.UNAVAILABLE_ENTITY,
                    MiniHomeSaveFailure.OUTBOX_MISMATCH,
                    MiniHomeSaveFailure.PAYLOAD_MISMATCH,
                    MiniHomeSaveFailure.REVISION_CONFLICT,
                    MiniHomeSaveFailure.PERMISSION_DENIED,
                    MiniHomeSaveFailure.MALFORMED_RESPONSE,
                )
            val remote = FakeRemote(layout(3))
            val repository = FirebaseMiniHomeRepository(database, remote)

            permanent.forEachIndexed { index, reason ->
                val operation = OperationId("operation-permanent-${index + 1}")
                val draft = layout(3).copy(name = "${reason.name} 편집")
                enqueue(operation, draft)
                database
                    .syncDao()
                    .markReconciliationRequired(
                        "account-a",
                        operation.value,
                        reason.name,
                        "persisted ${reason.name}",
                        3,
                        "authoritative-operation",
                        2,
                        3,
                        "a".repeat(64),
                    )

                val restored = repository.load() as MiniHomeLoadResult.Ready
                assertEquals(reason, restored.pending?.failure)
                val request = request(operation.value, draft)
                assertEquals(
                    MiniHomeSaveResult.RequiresReconciliation(reason),
                    repository.save(request),
                )
                assertTrue(remote.savedRequests.isEmpty())
                assertNotNull(database.syncDao().operation("account-a", operation.value))

                val corrected = reconcileCurrent(repository, request, reason)
                assertTrue(corrected is MiniHomeSaveResult.Reconciled)
                assertNull(database.syncDao().operation("account-a", operation.value))
            }
        }

    @Test
    fun `permanent reason remains owner scoped across account switch`() = runTest {
        val operation = OperationId("operation-account-switch")
        val draft = layout(3).copy(name = "A 계정 편집")
        enqueue(operation, draft)
        database
            .syncDao()
            .markReconciliationRequired(
                "account-a",
                operation.value,
                MiniHomeSaveFailure.UNAVAILABLE_ENTITY.name,
                "plant removed",
                3,
                null,
                null,
                null,
                null,
            )
        val remote = FakeRemote(layout(3))
        val repository = FirebaseMiniHomeRepository(database, remote)
        repository.load()

        remote.account = AccountId("account-b")
        val switched = repository.load() as MiniHomeLoadResult.Ready

        assertEquals(AccountId("account-b"), switched.accountId)
        assertNull(switched.pending)
        assertNotNull(database.syncDao().operation("account-a", operation.value))
        assertTrue(remote.savedRequests.isEmpty())
    }

    @Test
    fun `conflict caches authoritative revision and retains the stale outbox`() = runTest {
        val remote = FakeRemote(layout(5), RemoteMiniHomeSaveResult.Conflict(Revision(5)))
        val repository = FirebaseMiniHomeRepository(database, remote)
        val draft = layout(3).copy(name = "내 편집본")

        val result =
            repository.save(
                MiniHomeSaveRequest(
                    AccountId("account-a"),
                    OperationId("operation-layout-2"),
                    Revision(3),
                    draft,
                )
            )

        assertEquals(
            MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.REVISION_CONFLICT),
            result,
        )
        assertEquals(5L, database.cacheDao().miniHome("account-a")?.revision)
        assertNotNull(database.syncDao().operation("account-a", "operation-layout-2"))
        assertEquals(1, remote.savedRequests.size)
        repository.save(
            MiniHomeSaveRequest(
                AccountId("account-a"),
                OperationId("operation-layout-2"),
                Revision(3),
                draft,
            )
        )
        assertEquals(1, remote.savedRequests.size)
        assertEquals(
            MiniHomeDiscardResult.Consumed,
            abandonCurrent(repository, "account-a", "operation-layout-2"),
        )
        assertEquals(null, database.syncDao().operation("account-a", "operation-layout-2"))
    }

    private suspend fun abandonCurrent(
        repository: FirebaseMiniHomeRepository,
        accountId: String,
        operationId: String,
    ): MiniHomeDiscardResult {
        val row = requireNotNull(database.syncDao().operation(accountId, operationId))
        return repository.abandon(
            MiniHomeDiscardHandle(
                AccountId(row.accountId),
                row.aggregateType,
                row.operationId,
                row.lineageId,
                row.rowHandleId,
                row.rowVersion,
            )
        )
    }

    private suspend fun reconcileCurrent(
        repository: FirebaseMiniHomeRepository,
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
    ): MiniHomeSaveResult {
        val row =
            requireNotNull(
                database.syncDao().operation(request.accountId.value, request.operationId.value)
            )
        return repository.reconcile(
            request,
            failure,
            MiniHomeDiscardHandle(
                AccountId(row.accountId),
                row.aggregateType,
                row.operationId,
                row.lineageId,
                row.rowHandleId,
                row.rowVersion,
            ),
        )
    }

    private suspend fun enqueueRaw(
        operation: OperationId,
        payload: String,
        payloadHash: String?,
        accountId: String = "account-a",
    ) {
        database
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    operation.value,
                    accountId,
                    "miniHomeLayouts",
                    "home-a",
                    "REPLACE",
                    3,
                    payload,
                    1,
                    payloadHash = payloadHash,
                    lineageId = operation.value,
                )
            )
    }

    private fun legacyPayload(
        operation: OperationId,
        rawName: String,
        owner: String = "account-a",
    ): String {
        val canonical =
            RestoredMiniHomeDraft(
                AccountId(owner),
                operation,
                Revision(3),
                layout(3),
            )
        val root = Json.parseToJsonElement(MiniHomeDraftCodec.encode(canonical)).jsonObject
        return JsonObject(root + ("name" to JsonPrimitive(rawName))).toString()
    }

    private fun exactLegacyHash(payload: String): String {
        val decoded =
            MiniHomeDraftCodec.decodePersisted(payload, null)
                as PersistedMiniHomeEnvelopeDecode.Decoded
        return requireNotNull(decoded.envelope.exactPayloadHash())
    }

    private suspend fun enqueue(operation: OperationId, draft: MiniHomeLayout) {
        val encoded =
            MiniHomeDraftCodec.encode(
                RestoredMiniHomeDraft(
                    AccountId("account-a"),
                    operation,
                    Revision(3),
                    draft,
                )
            )
        database
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    operation.value,
                    "account-a",
                    "miniHomeLayouts",
                    draft.id.value,
                    "REPLACE",
                    3,
                    encoded,
                    1,
                    payloadHash = MiniHomePayloadHash.create(Revision(3), draft),
                    lineageId = operation.value,
                )
            )
    }

    private fun authoritativeInventory(
        itemId: String,
        name: String,
        category: ItemCategory,
        identity: com.planterior.helper.core.model.CatalogMediaIdentity,
        generation: Long = 1,
        partial: Boolean = false,
    ): AuthoritativeInventory {
        val account = AccountId("account-a")
        val catalog =
            listOf(
                AuthoritativeCatalogItem(
                    ItemId(itemId),
                    name,
                    "$name 설명",
                    category,
                    identity,
                    null,
                    Revision(generation),
                    generation,
                )
            )
        val owned =
            listOf(
                AuthoritativeOwnedItem(
                    ItemId(itemId),
                    generation,
                    applied = false,
                    revision = Revision(generation),
                    availability = AuthoritativeInventoryAvailability.AVAILABLE,
                    catalogSnapshot = null,
                )
            )
        return AuthoritativeInventory(
            INVENTORY_CONTRACT_VERSION,
            account,
            catalog,
            owned,
            registeredPlantCount = 1,
            loadedAtEpochMillis = generation,
            partial = partial,
            generation = generation,
            snapshotHash = authoritativeInventorySnapshotHash(account, catalog, owned, 1, partial),
        )
    }

    private suspend fun applyCoherentRoomSnapshot(
        target: PlanteriorDatabase,
        value: MiniHomeLayout,
        generation: Long,
    ) {
        target.withTransaction {
            val inventory =
                requireNotNull(
                    target
                        .cacheDao()
                        .currentInventoryCache("account-a")
                        ?.verifiedAuthoritativeInventoryOrNull(AccountId("account-a"))
                )
            target.cacheDao().applyAuthoritativeMiniHome(cacheLayoutWrite(value, generation))
            target
                .cacheDao()
                .applyAuthoritativeInventory(
                    inventory.cacheWrite(snapshotToken(generation), generation)
                )
        }
    }

    private fun snapshotToken(generation: Long): String = generation.toString(16).padStart(64, '0')

    private fun cacheLayoutWrite(
        value: MiniHomeLayout,
        generation: Long,
    ): AuthoritativeMiniHomeCacheWrite.Layout =
        AuthoritativeMiniHomeCacheWrite.Layout(
            accountId = "account-a",
            generation = generation,
            operationId = "sync-operation-$generation",
            payloadHash = generation.toString().padStart(64, '0'),
            home =
                CachedMiniHomeEntity(
                    "account-a",
                    value.id.value,
                    value.name,
                    value.placements.count { it.target is MiniHomePlacementTarget.Plant },
                    value.revision.value,
                    value.updatedAt.toEpochMilli(),
                ),
            placements =
                value.placements.map {
                    CachedMiniHomePlacementEntity(
                        "account-a",
                        it.id.value,
                        value.id.value,
                        (it.target as? MiniHomePlacementTarget.Plant)?.plantId?.value,
                        (it.target as? MiniHomePlacementTarget.Decoration)?.itemId?.value,
                        it.position.normalizedX.value,
                        it.position.normalizedY.value,
                        it.zIndex.value,
                        value.revision.value,
                    )
                },
            snapshotToken = snapshotToken(generation),
            snapshotGeneration = generation,
        )

    private fun request(operationId: String, draft: MiniHomeLayout) =
        MiniHomeSaveRequest(
            AccountId("account-a"),
            OperationId(operationId),
            Revision(3),
            draft,
        )

    private fun placement(id: String, plantId: String, position: GridPosition) =
        MiniHomePlacement(
            PlacementId(id),
            MiniHomePlacementTarget.Plant(PersonalPlantId(plantId)),
            position,
            MiniHomeZIndex(0),
        )

    private fun decoration(id: String, itemId: String, position: GridPosition) =
        MiniHomePlacement(
            PlacementId(id),
            MiniHomePlacementTarget.Decoration(ItemId(itemId)),
            position,
            MiniHomeZIndex(0),
        )

    private fun layout(revision: Long) =
        MiniHomeLayout(
            MiniHomeId("home-a"),
            "저장된 방",
            listOf(
                MiniHomePlacement(
                    PlacementId("placement-a"),
                    MiniHomePlacementTarget.Plant(PersonalPlantId("plant-a")),
                    GridPosition(2, 2),
                    MiniHomeZIndex(0),
                )
            ),
            Revision(revision),
            Instant.ofEpochMilli(revision),
        )

    private class FakeRemote(
        var layout: MiniHomeLayout,
        var saveResult: RemoteMiniHomeSaveResult =
            RemoteMiniHomeSaveResult.Applied(layout.revision.next()),
    ) : MiniHomeRemoteDataSource {
        var account = AccountId("account-a")
        var failLoad = false
        var loadCalls = 0
        var plants = listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null))
        var decorations = emptyList<MiniHomeDecorationChoice>()
        var authoritativeInventory =
            emptyAuthoritativeInventory(account, maxOf(1, layout.revision.value))
        var committedOperationId: OperationId? = null
        var committedExpectedRevision: Revision? = null
        var committedPayloadHash: String? = null
        var cacheGeneration = layout.revision.value
        var onLoad: (suspend () -> Unit)? = null
        var onSave: (suspend () -> Unit)? = null
        val savedRequests = mutableListOf<MiniHomeSaveRequest>()

        override fun activeAccount(): AccountId = account

        override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
            loadCalls += 1
            if (failLoad) error("offline")
            onLoad?.invoke()
            cacheGeneration = maxOf(cacheGeneration, layout.revision.value)
            return RemoteMiniHomeSnapshot(
                account,
                layout,
                plants,
                decorations,
                committedOperationId,
                committedExpectedRevision,
                committedPayloadHash,
                cacheGeneration = cacheGeneration,
                cacheOperationId =
                    committedOperationId?.value ?: "legacy-cache-${layout.revision.value}",
                cachePayloadHash = committedPayloadHash ?: "0".repeat(64),
                authoritativeInventory =
                    authoritativeInventory.takeIf { it.accountId == account }
                        ?: emptyAuthoritativeInventory(account, maxOf(1, cacheGeneration)),
            )
        }

        override suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult {
            savedRequests += request
            if (saveResult is RemoteMiniHomeSaveResult.Applied) {
                val revision = (saveResult as RemoteMiniHomeSaveResult.Applied).revision
                layout =
                    request.layout.copy(
                        revision = revision,
                        updatedAt = Instant.ofEpochMilli(revision.value),
                    )
                committedOperationId = request.operationId
                committedExpectedRevision = request.expectedRevision
                committedPayloadHash =
                    MiniHomePayloadHash.create(request.expectedRevision, request.layout)
                cacheGeneration += 1
            }
            onSave?.invoke()
            return saveResult
        }
    }

    private class CommitThenNetworkRemote(initial: MiniHomeLayout) : MiniHomeRemoteDataSource {
        private val account = AccountId("account-a")
        var layout = initial
        var saveCalls = 0
        private var committedOperationId: OperationId? = null
        private var committedExpectedRevision: Revision? = null
        private var committedPayloadHash: String? = null

        override fun activeAccount(): AccountId = account

        override suspend fun load(accountId: AccountId) =
            RemoteMiniHomeSnapshot(
                account,
                layout,
                listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)),
                emptyList(),
                committedOperationId,
                committedExpectedRevision,
                committedPayloadHash,
                authoritativeInventory =
                    emptyAuthoritativeInventory(account, maxOf(1, layout.revision.value)),
            )

        override suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult {
            saveCalls += 1
            layout =
                request.layout.copy(
                    revision = request.expectedRevision.next(),
                    updatedAt = Instant.ofEpochMilli(request.expectedRevision.next().value),
                )
            committedOperationId = request.operationId
            committedExpectedRevision = request.expectedRevision
            committedPayloadHash =
                MiniHomePayloadHash.create(request.expectedRevision, request.layout)
            return RemoteMiniHomeSaveResult.Failed(
                MiniHomeSaveFailure.NETWORK,
                "callable response unavailable",
            )
        }
    }

    private class ResponseLossRemote(initial: MiniHomeLayout) : MiniHomeRemoteDataSource {
        private val account = AccountId("account-a")
        var layout = initial
        val operations = mutableListOf<String>()
        private var committedPayloadHash: String? = null
        private var firstReceiptLoad = true

        override fun activeAccount(): AccountId = account

        override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
            if (firstReceiptLoad && operations.isNotEmpty()) {
                firstReceiptLoad = false
                error("response lost before authoritative read")
            }
            return RemoteMiniHomeSnapshot(
                account,
                layout,
                listOf(MiniHomePlantChoice(PersonalPlantId("plant-a"), "몬스테라", null)),
                emptyList(),
                operations.lastOrNull()?.let(::OperationId),
                if (operations.isEmpty()) null else Revision(3),
                committedPayloadHash,
                authoritativeInventory =
                    emptyAuthoritativeInventory(account, maxOf(1, layout.revision.value)),
            )
        }

        override suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult {
            operations += request.operationId.value
            committedPayloadHash =
                MiniHomePayloadHash.create(request.expectedRevision, request.layout)
            if (operations.size == 1) {
                layout =
                    request.layout.copy(
                        revision = request.expectedRevision.next(),
                        updatedAt = Instant.ofEpochMilli(request.expectedRevision.next().value),
                    )
                return RemoteMiniHomeSaveResult.Applied(layout.revision)
            }
            return RemoteMiniHomeSaveResult.Duplicate(layout.revision)
        }
    }

    @Test
    fun `post-decode transaction return stages are explicit and ordered`() {
        val root = repositoryRoot()
        val diagnostics =
            root
                .resolve(
                    "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                        "MiniHomeCacheConflictDiagnostics.kt"
                )
                .readText()
        val repository =
            root
                .resolve(
                    "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                        "FirebaseMiniHomeRepository.kt"
                )
                .readText()
        val receipt =
            root
                .resolve(
                    "app/src/debug/kotlin/com/planterior/helper/minihome/" +
                        "Todo18MiniHomeLoadDiagnosticReceipt.kt"
                )
                .readText()

        assertCode(
            diagnostics,
            "TRANSACTION_BODY_RETURNED(\"cache-transaction-body-returned\")",
            "TRANSACTION_SCOPE_RETURNED(\"cache-transaction-scope-returned\")",
        )
        assertCode(receipt, "put(\"stage\", observation.receiptStage)")
        assertOrdered(
            repository,
            "CoherentMiniHomeCacheApply.Current(",
            "MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_RETURNED",
            "MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_SCOPE_RETURNED",
            "MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_RETURNED",
        )
    }

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { assertTrue("Missing code token: $it", code.contains(it)) }
    }

    private fun assertOrdered(code: String, vararg tokens: String) {
        val positions = tokens.map(code::indexOf)
        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered code token: $token", position >= 0)
        }
        assertEquals(positions.sorted(), positions)
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}

private fun emptyAuthoritativeInventory(
    accountId: AccountId,
    generation: Long = 1,
): AuthoritativeInventory =
    AuthoritativeInventory(
        contractVersion = INVENTORY_CONTRACT_VERSION,
        accountId = accountId,
        catalog = emptyList(),
        owned = emptyList(),
        registeredPlantCount = 0,
        loadedAtEpochMillis = generation,
        partial = false,
        generation = generation,
        snapshotHash =
            authoritativeInventorySnapshotHash(
                accountId,
                emptyList(),
                emptyList(),
                registeredPlantCount = 0,
                partial = false,
            ),
    )
