package com.planterior.helper.feature.minihome

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.data.InconsistentMiniHomeLayoutException
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.MiniHomeCacheWatermarkEntity
import com.planterior.helper.core.database.MiniHomeCacheWatermarkKind
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun `offline migrated cache is visible then normal load bootstraps verified missing state`() =
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
                        )
                    }

                    override suspend fun save(
                        request: MiniHomeSaveRequest
                    ): RemoteMiniHomeSaveResult = error("not used")
                }
            val repository = FirebaseMiniHomeRepository(database, remote)

            val cached = repository.load() as MiniHomeLoadResult.Ready
            offline = false
            val bootstrapped = repository.load() as MiniHomeLoadResult.Ready

            assertTrue(cached.stale)
            assertEquals("migrated revision three", cached.committed.name)
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
    fun `irrecoverable remote name cannot overwrite a valid stale cache`() = runTest {
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "안전한 캐시", 0, 2, 2))
        val remote = FakeRemote(layout(3).copy(name = "A\u202EB"))

        val loaded = FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

        assertTrue(loaded.stale)
        assertEquals("안전한 캐시", loaded.committed.name)
        assertEquals("안전한 캐시", database.cacheDao().miniHome("account-a")?.name)
    }

    @Test
    fun `offline recoverable legacy cache is displayed as NFC and safely rewritten`() = runTest {
        database
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "e\u0301", 0, 2, 2))
        val remote = FakeRemote(layout(3)).apply { failLoad = true }

        val loaded = FirebaseMiniHomeRepository(database, remote).load() as MiniHomeLoadResult.Ready

        assertEquals(1, remote.loadCalls)
        assertTrue(loaded.stale)
        assertEquals("é", loaded.committed.name)
        assertEquals("é", database.cacheDao().miniHome("account-a")?.name)
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
}
