package com.planterior.helper.feature.minihome

import androidx.room.withTransaction
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeInventoryCondition
import com.planterior.helper.core.data.AuthoritativeMiniHomeLayoutRead
import com.planterior.helper.core.data.AuthoritativeMiniHomeSnapshotReader
import com.planterior.helper.core.data.AuthoritativeOwnedCatalogSnapshot
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.data.InconsistentInventoryException
import com.planterior.helper.core.data.authoritativeInventorySnapshotHash
import com.planterior.helper.core.data.cacheWrite
import com.planterior.helper.core.data.verifiedAuthoritativeInventoryOrNull
import com.planterior.helper.core.database.AuthoritativeInventoryCacheWrite
import com.planterior.helper.core.database.AuthoritativeMiniHomeCacheWrite
import com.planterior.helper.core.database.CachedInventoryState
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedMiniHomeLayoutState
import com.planterior.helper.core.database.CachedMiniHomePlacementEntity
import com.planterior.helper.core.database.CachedMiniHomeSnapshotState
import com.planterior.helper.core.database.CachedOwnedItemEntity
import com.planterior.helper.core.database.CachedShopItemEntity
import com.planterior.helper.core.database.InventoryCacheApplyResult
import com.planterior.helper.core.database.MiniHomeCacheApplyResult
import com.planterior.helper.core.database.MiniHomeCacheWatermarkKind
import com.planterior.helper.core.database.OperationOutboxCompareAndSetResult
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PersistedOperationDiscardResult
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RemoteMiniHomeSnapshot(
    val accountId: AccountId,
    val layout: MiniHomeLayout?,
    val plants: List<MiniHomePlantChoice>,
    val decorations: List<MiniHomeDecorationChoice>,
    val committedOperationId: OperationId? = null,
    val committedExpectedRevision: Revision? = null,
    val committedPayloadHash: String? = null,
    val cacheGeneration: Long = maxOf(1, layout?.revision?.value ?: 0),
    val cacheOperationId: String? = committedOperationId?.value,
    val cachePayloadHash: String? = committedPayloadHash,
    val deletionTombstoneId: String? = if (layout == null) "initial-missing" else null,
    val authoritativeAtEpochMillis: Long = layout?.updatedAt?.toEpochMilli() ?: 0,
    val authoritativeInventory: AuthoritativeInventory,
    val snapshotToken: String = "0".repeat(64),
    val snapshotGeneration: Long = maxOf(cacheGeneration, authoritativeInventory.generation),
)

private sealed interface PersistedMiniHomeOperationDecode {
    data class Canonical(
        val envelope: PersistedMiniHomeEnvelope,
        val draft: RestoredMiniHomeDraft,
        val exactPayloadHash: String,
    ) : PersistedMiniHomeOperationDecode

    data class Quarantined(
        val details: String,
        val failure: MiniHomeSaveFailure = MiniHomeSaveFailure.MALFORMED_RESPONSE,
        val envelope: PersistedMiniHomeEnvelope? = null,
        val draft: RestoredMiniHomeDraft? = null,
        val recomputedPayloadHash: String? = null,
    ) : PersistedMiniHomeOperationDecode
}

private sealed interface CachedMiniHomeRecovery {
    data object Missing : CachedMiniHomeRecovery

    data object Irrecoverable : CachedMiniHomeRecovery

    data class Ready(
        val layout: MiniHomeLayout,
        val legacyName: String? = null,
    ) : CachedMiniHomeRecovery
}

private sealed interface CoherentMiniHomeCacheApply {
    data class Current(
        val layout: CachedMiniHomeLayoutState,
        val inventory: AuthoritativeInventory,
    ) : CoherentMiniHomeCacheApply

    data object Conflict : CoherentMiniHomeCacheApply
}

private class CoherentMiniHomeCacheConflict(
    val category: MiniHomeCacheConflictCategory,
    val predicate: MiniHomeCacheConflictPredicate,
    val operands: Map<String, String?>,
) : RuntimeException()

private data class MiniHomePublicationToken(
    val accountId: AccountId,
    val ownerGeneration: Long,
    val requestGeneration: Long,
)

private data class MiniHomePublicationVersion(
    val snapshotGeneration: Long,
    val layoutGeneration: Long,
    val layoutRevision: Long?,
    val layoutKind: MiniHomeCacheWatermarkKind?,
    val inventoryGeneration: Long,
) {
    fun dominates(other: MiniHomePublicationVersion): Boolean {
        if (snapshotGeneration < other.snapshotGeneration) return false
        if (layoutGeneration < other.layoutGeneration) return false
        if (inventoryGeneration < other.inventoryGeneration) return false
        if (
            layoutKind == MiniHomeCacheWatermarkKind.PRESENT &&
                other.layoutKind == MiniHomeCacheWatermarkKind.PRESENT &&
                requireNotNull(layoutRevision) < requireNotNull(other.layoutRevision)
        ) {
            return false
        }
        return true
    }
}

private data class CachedMiniHomePublication(
    val layoutState: CachedMiniHomeLayoutState?,
    val recovery: CachedMiniHomeRecovery,
    val inventory: AuthoritativeInventory?,
    val plants: List<MiniHomePlantChoice>,
    val version: MiniHomePublicationVersion,
    val corruptInventoryGeneration: Pair<Long, String>? = null,
    val incoherentSnapshot: Boolean = false,
)

private enum class MiniHomePublicationDecision {
    PUBLISH,
    REREAD,
    FORBIDDEN,
}

sealed interface RemoteMiniHomeSaveResult {
    data class Applied(val revision: Revision) : RemoteMiniHomeSaveResult

    data class Duplicate(val revision: Revision) : RemoteMiniHomeSaveResult

    data class Conflict(val actualRevision: Revision) : RemoteMiniHomeSaveResult

    data class Failed(
        val failure: MiniHomeSaveFailure,
        val details: String? = null,
        val committedOperationId: OperationId? = null,
        val committedExpectedRevision: Revision? = null,
        val committedRevision: Revision? = null,
        val committedPayloadHash: String? = null,
    ) : RemoteMiniHomeSaveResult
}

interface MiniHomeRemoteDataSource {
    fun activeAccount(): AccountId?

    suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot

    suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult
}

@JvmInline value class MiniHomePublicationReadIdentity(val value: Long)

@JvmInline value class MiniHomeLoadIdentity(val value: Long)

data class MiniHomePendingReadIdentity(
    val loadId: MiniHomeLoadIdentity,
    val queryOrdinal: Long,
)

sealed interface MiniHomePendingReadOutcome {
    data object Returned : MiniHomePendingReadOutcome

    data class Threw(val failure: Throwable) : MiniHomePendingReadOutcome

    data class Cancelled(val failure: CancellationException) : MiniHomePendingReadOutcome
}

class FirebaseMiniHomeRepository(
    private val database: PlanteriorDatabase,
    private val remote: MiniHomeRemoteDataSource,
    private val now: () -> Instant = Instant::now,
    private val beforeCacheApply: suspend (AccountId) -> Unit = {},
    private val afterCacheApply: suspend (AccountId, Boolean) -> Unit = { _, _ -> },
    private val beforePublicationRead:
        suspend (AccountId, MiniHomePublicationReadIdentity) -> Unit =
        { _, _ ->
        },
    private val afterPublicationRead: suspend (AccountId, MiniHomePublicationReadIdentity) -> Unit =
        { _, _ ->
        },
    private val beforePendingRead: suspend (AccountId, MiniHomePendingReadIdentity) -> Unit =
        { _, _ ->
        },
    private val afterPendingRead:
        suspend (AccountId, MiniHomePendingReadIdentity, MiniHomePendingReadOutcome) -> Unit =
        { _, _, _ ->
        },
) : MiniHomeRepository {
    private val ownerOperations = ConcurrentHashMap<String, Mutex>()
    private val recentSaveOutcomes = ConcurrentHashMap<String, RegisteredSaveOutcome>()
    private val publicationMutex = Mutex()
    private var observedPublicationOwner: AccountId? = null
    private var ownerGeneration = 0L
    private var requestGeneration = 0L
    private val currentOwnerRequest = mutableMapOf<String, Long>()
    private val lastPublished = mutableMapOf<String, MiniHomePublicationVersion>()
    private val publicationReadSequence = AtomicLong()
    private val loadSequence = AtomicLong()
    private val pendingReadSequence = AtomicLong()

    override suspend fun load(): MiniHomeLoadResult {
        val account = remote.activeAccount() ?: return MiniHomeLoadResult.Forbidden
        val loadId = MiniHomeLoadIdentity(loadSequence.incrementAndGet())
        return withOwnerOperation(account) {
            val token =
                beginPublication(account) ?: return@withOwnerOperation MiniHomeLoadResult.Forbidden
            loadLocked(account, token, loadId)
        }
    }

    private suspend fun loadLocked(
        account: AccountId,
        token: MiniHomePublicationToken,
        loadId: MiniHomeLoadIdentity,
    ): MiniHomeLoadResult {
        return try {
            val pendingBeforeLoad = activePendingOperation(account, loadId)
            val snapshot = remote.load(account).recoverLegacyName()
            if (snapshot.accountId != account || remote.activeAccount() != account) {
                return MiniHomeLoadResult.Forbidden
            }
            notifyCacheApplyEntered(account)
            val applied = cache(account, snapshot)
            notifyCacheApplyReturned(account, applied)
            if (remote.activeAccount() != account) return MiniHomeLoadResult.Forbidden
            publishCurrentRoomWinner(
                account = account,
                token = token,
                stale = applied is CoherentMiniHomeCacheApply.Conflict,
                fresh = snapshot.takeIf { applied is CoherentMiniHomeCacheApply.Current },
                pendingBeforeLoad = pendingBeforeLoad,
                loadId = loadId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishCurrentRoomWinner(account, token, stale = true, loadId = loadId)
        }
    }

    private suspend fun notifyCacheApplyEntered(account: AccountId) {
        observeCacheDiagnostic { beforeCacheApply(account) }
    }

    private suspend fun notifyCacheApplyReturned(
        account: AccountId,
        applied: CoherentMiniHomeCacheApply,
    ) {
        observeCacheDiagnostic {
            afterCacheApply(account, applied is CoherentMiniHomeCacheApply.Current)
        }
    }

    private suspend fun observeCacheDiagnostic(observe: suspend () -> Unit) {
        try {
            observe()
        } catch (error: CancellationException) {
            throw error
        } catch (_: AssertionError) {} catch (_: Exception) {}
    }

    override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
        withOwnerOperation(request.accountId) { saveLocked(request) }

    private suspend fun saveLocked(request: MiniHomeSaveRequest): MiniHomeSaveResult {
        if (remote.activeAccount() != request.accountId) return MiniHomeSaveResult.Forbidden
        MiniHomeRequestContract.validate(request)?.let { violation ->
            return MiniHomeSaveResult.RequiresCorrection(
                MiniHomeSaveFailure.INVALID_REQUEST,
                "field=${violation.field};details=${violation.details}",
            )
        }
        val encoded =
            MiniHomeDraftCodec.encode(
                RestoredMiniHomeDraft(
                    request.accountId,
                    request.operationId,
                    request.expectedRevision,
                    request.layout,
                    request.lineageId,
                    request.supersedesOperationId,
                )
            )
        val payloadHash = MiniHomePayloadHash.create(request.expectedRevision, request.layout)
        val outbox =
            OperationOutboxEntity(
                request.operationId.value,
                request.accountId.value,
                OUTBOX_TYPE,
                request.layout.id.value,
                "REPLACE",
                request.expectedRevision.value,
                encoded,
                now().toEpochMilli(),
                payloadHash = payloadHash,
                lineageId = request.lineageId.value,
                supersedesOperationId = request.supersedesOperationId?.value,
            )
        var operation =
            try {
                val inserted = database.syncDao().insertOperation(outbox)
                val current =
                    if (inserted == -1L) {
                        database
                            .syncDao()
                            .operation(request.accountId.value, request.operationId.value)
                            ?: return pendingChanged(request.accountId)
                    } else {
                        outbox
                    }
                if (inserted == -1L) {
                    val transition =
                        MiniHomeOutboxTransitionTable.transition(
                            current.state,
                            current.lastErrorCode,
                        )
                    if (transition.requiresCorrection) {
                        return MiniHomeSaveResult.RequiresCorrection(
                            requireNotNull(transition.reason),
                            current.failureDetails,
                            current.discardHandle(),
                        )
                    }
                    if (transition.requiresExplicitReconciliation) {
                        return MiniHomeSaveResult.RequiresReconciliation(
                            transition.reason?.takeIf { it.requiresReconciliation }
                                ?: MiniHomeSaveFailure.OUTBOX_MISMATCH,
                            current.discardHandle(),
                        )
                    }
                    if (!current.matches(outbox)) {
                        val marked =
                            compareAndSet(current) {
                                it.copy(
                                    state = PHASE_RECONCILIATION_REQUIRED,
                                    lastErrorCode = MiniHomeSaveFailure.OUTBOX_MISMATCH.name,
                                    failureDetails =
                                        "save request differs from the persisted operation payload",
                                )
                            }
                        if (marked == null) return pendingChanged(request.accountId)
                        return MiniHomeSaveResult.RequiresReconciliation(
                            MiniHomeSaveFailure.OUTBOX_MISMATCH,
                            marked.discardHandle(),
                        )
                    }
                }
                compareAndSet(current) {
                    it.copy(
                        state = PHASE_MAY_HAVE_COMMITTED,
                        actualRevision = null,
                        lastErrorCode = null,
                        failureDetails = null,
                    )
                } ?: return pendingChanged(request.accountId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MiniHomeSaveResult.Failed(MiniHomeSaveFailure.DATABASE)
            }
        return withContext(NonCancellable) {
            completeSaveAcrossRemoteBoundary(request, operation, payloadHash).also { result ->
                recentSaveOutcomes[request.accountId.value] =
                    RegisteredSaveOutcome(request.operationId, result)
            }
        }
    }

    private suspend fun completeSaveAcrossRemoteBoundary(
        request: MiniHomeSaveRequest,
        initialOperation: OperationOutboxEntity,
        payloadHash: String,
    ): MiniHomeSaveResult {
        var operation = initialOperation
        if (remote.activeAccount() != request.accountId) return MiniHomeSaveResult.Forbidden
        val result =
            try {
                remote.save(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
            } catch (_: Exception) {
                RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.MALFORMED_RESPONSE)
            }
        if (remote.activeAccount() != request.accountId) return MiniHomeSaveResult.Forbidden
        return try {
            when (result) {
                is RemoteMiniHomeSaveResult.Applied,
                is RemoteMiniHomeSaveResult.Duplicate -> {
                    val revision =
                        if (result is RemoteMiniHomeSaveResult.Applied) {
                            result.revision
                        } else {
                            (result as RemoteMiniHomeSaveResult.Duplicate).revision
                        }
                    operation =
                        recordReceipt(
                            operation,
                            request.operationId,
                            request.expectedRevision,
                            revision,
                            payloadHash,
                        ) ?: return pendingChanged(request.accountId)
                    reconcileApplied(request, operation, revision)
                }
                is RemoteMiniHomeSaveResult.Conflict ->
                    reconcileConflict(request, operation, result.actualRevision)
                is RemoteMiniHomeSaveResult.Failed -> {
                    val updated =
                        compareAndSet(operation) { current ->
                            current.copy(
                                state =
                                    if (result.failure.permanent) {
                                        PHASE_RECONCILIATION_REQUIRED
                                    } else {
                                        PHASE_MAY_HAVE_COMMITTED
                                    },
                                actualRevision = result.committedRevision?.value,
                                lastErrorCode = result.failure.name,
                                failureDetails = result.details ?: result.failure.name,
                                committedOperationId =
                                    result.committedOperationId?.value
                                        ?: current.committedOperationId,
                                committedExpectedRevision =
                                    result.committedExpectedRevision?.value
                                        ?: current.committedExpectedRevision,
                                committedRevision =
                                    result.committedRevision?.value ?: current.committedRevision,
                                committedPayloadHash =
                                    result.committedPayloadHash ?: current.committedPayloadHash,
                            )
                        }
                    if (updated == null) return pendingChanged(request.accountId)
                    when {
                        result.failure.requiresCorrection ->
                            MiniHomeSaveResult.RequiresCorrection(
                                result.failure,
                                result.details,
                                updated.discardHandle(),
                            )
                        result.failure.requiresReconciliation ->
                            MiniHomeSaveResult.RequiresReconciliation(
                                result.failure,
                                updated.discardHandle(),
                            )
                        else ->
                            MiniHomeSaveResult.Failed(
                                result.failure,
                                updated.discardHandle(),
                            )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MiniHomeSaveResult.Failed(
                MiniHomeSaveFailure.DATABASE,
                operation.discardHandle(),
            )
        }
    }

    override suspend fun reconcile(
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
    ): MiniHomeSaveResult = pendingChanged(request.accountId)

    override suspend fun reconcile(
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
        discardHandle: MiniHomeDiscardHandle?,
    ): MiniHomeSaveResult =
        withOwnerOperation(request.accountId) {
            reconcileLocked(request, failure, discardHandle)
        }

    private suspend fun reconcileLocked(
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
        discardHandle: MiniHomeDiscardHandle?,
    ): MiniHomeSaveResult {
        require(failure.requiresReconciliation)
        if (remote.activeAccount() != request.accountId) return MiniHomeSaveResult.Forbidden
        val handle = discardHandle ?: return pendingChanged(request.accountId)
        if (handle.accountId != request.accountId || handle.aggregateType != OUTBOX_TYPE) {
            return pendingChanged(request.accountId)
        }
        var operation =
            try {
                database
                    .syncDao()
                    .operationByHandle(
                        handle.accountId.value,
                        handle.aggregateType,
                        handle.rowOperationId,
                        handle.rowLineageId,
                        handle.rowHandleId,
                        handle.rowVersion,
                    ) ?: return pendingChanged(request.accountId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return pendingChanged(request.accountId)
            }
        val snapshot =
            try {
                remote.load(request.accountId).recoverLegacyName()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MiniHomeSaveResult.RequiresReconciliation(
                    failure,
                    operation.discardHandle(),
                )
            }
        if (
            remote.activeAccount() != request.accountId || snapshot.accountId != request.accountId
        ) {
            return MiniHomeSaveResult.Forbidden
        }
        val applied = cache(request.accountId, snapshot, OperationId(operation.operationId))
        if (applied is CoherentMiniHomeCacheApply.Conflict) {
            return MiniHomeSaveResult.RequiresReconciliation(
                MiniHomeSaveFailure.INCONSISTENT_RECEIPT,
                operation.discardHandle(),
            )
        }
        applied as CoherentMiniHomeCacheApply.Current
        val effective = snapshot.fromCache(applied.layout, applied.inventory)
        val authoritative = effective.layout ?: blankLayout()
        val persistedDraft = decodePersistedOperation(operation)
        if (persistedDraft is PersistedMiniHomeOperationDecode.Canonical) {
            operation =
                ensurePayloadHash(operation, persistedDraft.exactPayloadHash)
                    ?: return pendingChanged(request.accountId)
            operation =
                persistAuthoritativeReceipt(operation, effective)
                    ?: return pendingChanged(request.accountId)
            if (
                operation.receiptMatches(
                    effective,
                    persistedDraft,
                    persistedDraft.exactPayloadHash,
                ) &&
                    persistedDraft.draft.operationId == request.operationId &&
                    persistedDraft.draft.expectedRevision == request.expectedRevision &&
                    persistedDraft.draft.layout.sameContent(request.layout)
            ) {
                return when (val consumed = consume(operation)) {
                    is MiniHomeDiscardResult.Consumed -> MiniHomeSaveResult.Saved(authoritative)
                    is MiniHomeDiscardResult.Committed ->
                        MiniHomeSaveResult.Saved(consumed.authoritative)
                    is MiniHomeDiscardResult.StaleHandle ->
                        MiniHomeSaveResult.PendingChanged(consumed.current)
                    MiniHomeDiscardResult.Missing -> pendingChanged(request.accountId)
                    MiniHomeDiscardResult.OwnerMismatch,
                    MiniHomeDiscardResult.Rejected -> pendingChanged(request.accountId)
                }
            }
        } else {
            operation =
                persistAuthoritativeReceipt(operation, effective)
                    ?: return pendingChanged(request.accountId)
        }
        val plantIds = effective.plants.mapTo(mutableSetOf()) { it.id }
        val decorationIds = effective.decorations.mapTo(mutableSetOf()) { it.id }
        val retained =
            request.layout.placements.filter { placement ->
                when (val target = placement.target) {
                    is MiniHomePlacementTarget.Plant -> target.plantId in plantIds
                    is MiniHomePlacementTarget.Decoration -> target.itemId in decorationIds
                }
            }
        val corrected =
            request.layout.copy(
                id = authoritative.id,
                placements = MiniHomePlacementPolicy.layer(retained),
                revision = authoritative.revision,
                updatedAt = authoritative.updatedAt,
            )
        return when (val consumed = consume(operation)) {
            is MiniHomeDiscardResult.Consumed ->
                MiniHomeSaveResult.Reconciled(
                    failure,
                    authoritative,
                    effective.plants,
                    effective.decorations,
                    corrected,
                    request.layout.placements.size - retained.size,
                )
            is MiniHomeDiscardResult.Committed -> MiniHomeSaveResult.Saved(consumed.authoritative)
            is MiniHomeDiscardResult.StaleHandle ->
                MiniHomeSaveResult.PendingChanged(consumed.current)
            MiniHomeDiscardResult.Missing -> pendingChanged(request.accountId)
            MiniHomeDiscardResult.OwnerMismatch,
            MiniHomeDiscardResult.Rejected -> pendingChanged(request.accountId)
        }
    }

    override suspend fun abandonPending(
        accountId: AccountId,
        operationId: OperationId?,
    ): MiniHomeDiscardResult =
        withOwnerOperation(accountId) { abandonPendingLocked(accountId, operationId) }

    private suspend fun abandonPendingLocked(
        accountId: AccountId,
        operationId: OperationId?,
    ): MiniHomeDiscardResult {
        if (remote.activeAccount() != accountId) return MiniHomeDiscardResult.OwnerMismatch
        return try {
            val operation = activePendingOperation(accountId)
            if (remote.activeAccount() != accountId) {
                return MiniHomeDiscardResult.OwnerMismatch
            }
            if (operation == null) {
                val recent = recentSaveOutcomes[accountId.value]
                return if (
                    operationId != null &&
                        recent?.operationId == operationId &&
                        recent.result is MiniHomeSaveResult.Saved
                ) {
                    recentSaveOutcomes.remove(accountId.value, recent)
                    MiniHomeDiscardResult.Committed(recent.result.layout)
                } else {
                    MiniHomeDiscardResult.Missing
                }
            }
            if (operation.state == PHASE_MAY_HAVE_COMMITTED) {
                return resolveUncertainDiscard(accountId, operation.discardHandle())
            }
            when (val result = consume(operation)) {
                MiniHomeDiscardResult.Missing -> {
                    if (remote.activeAccount() != accountId) {
                        MiniHomeDiscardResult.OwnerMismatch
                    } else {
                        pendingSnapshot(accountId)?.let(MiniHomeDiscardResult::StaleHandle)
                            ?: MiniHomeDiscardResult.Missing
                    }
                }
                else -> result
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MiniHomeDiscardResult.Rejected
        }
    }

    override suspend fun abandon(
        accountId: AccountId,
        operationId: OperationId,
        lineageId: OperationId,
    ): MiniHomeDiscardResult = MiniHomeDiscardResult.Rejected

    override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
        withOwnerOperation(handle.accountId) {
            if (remote.activeAccount() != handle.accountId) {
                return@withOwnerOperation MiniHomeDiscardResult.OwnerMismatch
            }
            if (handle.aggregateType != OUTBOX_TYPE) {
                return@withOwnerOperation MiniHomeDiscardResult.Rejected
            }
            val operation =
                try {
                    database
                        .syncDao()
                        .operationByHandle(
                            handle.accountId.value,
                            handle.aggregateType,
                            handle.rowOperationId,
                            handle.rowLineageId,
                            handle.rowHandleId,
                            handle.rowVersion,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withOwnerOperation MiniHomeDiscardResult.Rejected
                }
            if (operation?.state == PHASE_MAY_HAVE_COMMITTED) {
                resolveUncertainDiscard(handle.accountId, handle)
            } else {
                consume(handle)
            }
        }

    private suspend fun resolveUncertainDiscard(
        accountId: AccountId,
        expectedHandle: MiniHomeDiscardHandle,
    ): MiniHomeDiscardResult {
        val token = beginPublication(accountId) ?: return MiniHomeDiscardResult.OwnerMismatch
        return when (
            val loaded =
                loadLocked(accountId, token, MiniHomeLoadIdentity(loadSequence.incrementAndGet()))
        ) {
            is MiniHomeLoadResult.Ready ->
                when {
                    loaded.stale -> MiniHomeDiscardResult.Rejected
                    loaded.pending == null -> MiniHomeDiscardResult.Committed(loaded.committed)
                    loaded.pending.discardHandle == null -> MiniHomeDiscardResult.Rejected
                    !loaded.pending.discardHandle.sameRowGeneration(expectedHandle) ->
                        MiniHomeDiscardResult.StaleHandle(loaded.pending)
                    else -> consume(loaded.pending.discardHandle)
                }
            MiniHomeLoadResult.Forbidden -> MiniHomeDiscardResult.OwnerMismatch
            MiniHomeLoadResult.Failed -> MiniHomeDiscardResult.Rejected
        }
    }

    private fun MiniHomeDiscardHandle.sameRowGeneration(other: MiniHomeDiscardHandle): Boolean =
        accountId == other.accountId &&
            aggregateType == other.aggregateType &&
            rowOperationId == other.rowOperationId &&
            rowLineageId == other.rowLineageId &&
            rowHandleId == other.rowHandleId

    private suspend fun consume(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult {
        if (remote.activeAccount() != handle.accountId) {
            return MiniHomeDiscardResult.OwnerMismatch
        }
        return when (
            val result =
                database
                    .syncDao()
                    .discardPersistedOperation(
                        handle.accountId.value,
                        handle.aggregateType,
                        handle.rowOperationId,
                        handle.rowLineageId,
                        handle.rowHandleId,
                        handle.rowVersion,
                    )
        ) {
            is PersistedOperationDiscardResult.Consumed -> MiniHomeDiscardResult.Consumed
            is PersistedOperationDiscardResult.Stale ->
                MiniHomeDiscardResult.StaleHandle(pendingSnapshot(handle.accountId))
            PersistedOperationDiscardResult.Missing -> MiniHomeDiscardResult.Missing
            PersistedOperationDiscardResult.Rejected -> MiniHomeDiscardResult.Rejected
        }
    }

    private suspend fun consume(operation: OperationOutboxEntity): MiniHomeDiscardResult =
        consume(operation.discardHandle())

    private suspend fun reconcileApplied(
        request: MiniHomeSaveRequest,
        operation: OperationOutboxEntity,
        receiptRevision: Revision,
    ): MiniHomeSaveResult {
        val snapshot =
            try {
                remote.load(request.accountId).recoverLegacyName()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                val marked =
                    compareAndSet(operation) {
                        it.copy(
                            state = PHASE_MAY_HAVE_COMMITTED,
                            lastErrorCode = MiniHomeSaveFailure.INCONSISTENT_RECEIPT.name,
                            failureDetails = "authoritative receipt could not be loaded",
                        )
                    }
                return if (marked == null) {
                    pendingChanged(request.accountId)
                } else {
                    MiniHomeSaveResult.Failed(
                        MiniHomeSaveFailure.INCONSISTENT_RECEIPT,
                        marked.discardHandle(),
                    )
                }
            }
        if (
            remote.activeAccount() != request.accountId || snapshot.accountId != request.accountId
        ) {
            return MiniHomeSaveResult.Forbidden
        }
        val applied = cache(request.accountId, snapshot, OperationId(operation.operationId))
        if (applied is CoherentMiniHomeCacheApply.Conflict) {
            return MiniHomeSaveResult.Failed(
                MiniHomeSaveFailure.INCONSISTENT_RECEIPT,
                operation.discardHandle(),
            )
        }
        applied as CoherentMiniHomeCacheApply.Current
        val effective = snapshot.fromCache(applied.layout, applied.inventory)
        val authoritative = effective.layout
        if (
            authoritative == null ||
                authoritative.revision != receiptRevision ||
                !effective.receiptMatches(request)
        ) {
            val failure = effective.mismatchReason(request)
            val marked =
                markReconciliationRequired(
                    operation,
                    failure.name,
                    mismatchDetails(operation, effective),
                    effective,
                ) ?: return pendingChanged(request.accountId)
            return MiniHomeSaveResult.RequiresReconciliation(
                marked.pendingFailure() ?: failure,
                marked.discardHandle(),
            )
        }
        return try {
            when (val consumed = consume(operation)) {
                MiniHomeDiscardResult.Consumed -> MiniHomeSaveResult.Saved(authoritative)
                is MiniHomeDiscardResult.Committed ->
                    MiniHomeSaveResult.Saved(consumed.authoritative)
                is MiniHomeDiscardResult.StaleHandle ->
                    MiniHomeSaveResult.PendingChanged(consumed.current)
                MiniHomeDiscardResult.Missing -> pendingChanged(request.accountId)
                MiniHomeDiscardResult.OwnerMismatch,
                MiniHomeDiscardResult.Rejected -> pendingChanged(request.accountId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MiniHomeSaveResult.Failed(
                MiniHomeSaveFailure.DATABASE,
                operation.discardHandle(),
            )
        }
    }

    private suspend fun reconcileConflict(
        request: MiniHomeSaveRequest,
        operation: OperationOutboxEntity,
        actualRevision: Revision,
    ): MiniHomeSaveResult {
        var current =
            compareAndSet(operation) {
                it.copy(
                    state = PHASE_RECONCILIATION_REQUIRED,
                    actualRevision = actualRevision.value,
                    lastErrorCode = MiniHomeSaveFailure.REVISION_CONFLICT.name,
                    failureDetails = "callable reported revision conflict",
                )
            } ?: return pendingChanged(request.accountId)
        val snapshot =
            try {
                remote.load(request.accountId).recoverLegacyName()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return MiniHomeSaveResult.RequiresReconciliation(
                    MiniHomeSaveFailure.REVISION_CONFLICT,
                    current.discardHandle(),
                )
            }
        if (
            remote.activeAccount() != request.accountId || snapshot.accountId != request.accountId
        ) {
            return MiniHomeSaveResult.Forbidden
        }
        current =
            persistAuthoritativeReceipt(current, snapshot)
                ?: return pendingChanged(request.accountId)
        cache(request.accountId, snapshot)
        return MiniHomeSaveResult.RequiresReconciliation(
            current.pendingFailure() ?: MiniHomeSaveFailure.REVISION_CONFLICT,
            current.discardHandle(),
        )
    }

    private suspend fun cache(
        account: AccountId,
        snapshot: RemoteMiniHomeSnapshot,
        operationId: OperationId? = null,
    ): CoherentMiniHomeCacheApply {
        require(snapshot.accountId == account)
        val layout = snapshot.layout
        val layoutWrite =
            if (layout == null) {
                AuthoritativeMiniHomeCacheWrite.Deletion(
                    account.value,
                    snapshot.cacheGeneration,
                    snapshot.deletionTombstoneId ?: "initial-missing",
                    snapshot.authoritativeAtEpochMillis,
                    snapshot.snapshotToken,
                    snapshot.snapshotGeneration,
                )
            } else {
                AuthoritativeMiniHomeCacheWrite.Layout(
                    account.value,
                    snapshot.cacheGeneration,
                    snapshot.cacheOperationId ?: "legacy-cache-${layout.revision.value}",
                    snapshot.cachePayloadHash ?: "0".repeat(64),
                    CachedMiniHomeEntity(
                        account.value,
                        layout.id.value,
                        layout.name,
                        layout.placements.count { placement ->
                            placement.target is MiniHomePlacementTarget.Plant
                        },
                        layout.revision.value,
                        layout.updatedAt.toEpochMilli(),
                    ),
                    layout.placements.map { placement ->
                        CachedMiniHomePlacementEntity(
                            account.value,
                            placement.id.value,
                            layout.id.value,
                            (placement.target as? MiniHomePlacementTarget.Plant)?.plantId?.value,
                            (placement.target as? MiniHomePlacementTarget.Decoration)
                                ?.itemId
                                ?.value,
                            placement.position.normalizedX.value,
                            placement.position.normalizedY.value,
                            placement.zIndex.value,
                            layout.revision.value,
                        )
                    },
                    snapshot.snapshotToken,
                    snapshot.snapshotGeneration,
                )
            }
        return try {
            database.withTransaction {
                val beforeLayout = database.cacheDao().currentMiniHomeCache(account.value)
                val layoutApplied = database.cacheDao().applyAuthoritativeMiniHome(layoutWrite)
                if (layoutApplied is MiniHomeCacheApplyResult.Conflict) {
                    val conflict = layoutConflict(layoutWrite, layoutApplied.current)
                    MiniHomeCacheConflictDiagnostics.observe(
                        cacheObservation(
                            MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                            account,
                            operationId,
                            MiniHomeCacheDiagnosticOutcome.CONFLICT,
                            MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                            conflict.first,
                            cacheWatermarks(beforeLayout, layoutApplied.current, layoutWrite) +
                                conflict.second,
                        )
                    )
                    throw CoherentMiniHomeCacheConflict(
                        MiniHomeCacheConflictCategory.LAYOUT_APPLY,
                        conflict.first,
                        cacheWatermarks(beforeLayout, layoutApplied.current, layoutWrite) +
                            conflict.second,
                    )
                }
                MiniHomeCacheConflictDiagnostics.observe(
                    cacheObservation(
                        MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                        account,
                        operationId,
                        layoutApplied.diagnosticOutcome(),
                        operands =
                            layoutSuccessOperands(beforeLayout, layoutApplied.current, layoutWrite),
                    )
                )
                val authoritative = snapshot.authoritativeInventory
                require(authoritative.accountId == account)
                val inventoryWrite =
                    authoritative.cacheWrite(
                        snapshot.snapshotToken,
                        snapshot.snapshotGeneration,
                    )
                val beforeInventory = database.cacheDao().currentInventoryCache(account.value)
                val inventoryApplied =
                    database.cacheDao().applyAuthoritativeInventory(inventoryWrite)
                if (inventoryApplied is InventoryCacheApplyResult.Conflict) {
                    val conflict = inventoryConflict(inventoryWrite, inventoryApplied.current)
                    val operands =
                        inventoryWatermarks(
                            beforeInventory,
                            inventoryApplied.current,
                            inventoryWrite,
                        ) + conflict.second
                    MiniHomeCacheConflictDiagnostics.observe(
                        cacheObservation(
                            MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                            account,
                            operationId,
                            MiniHomeCacheDiagnosticOutcome.CONFLICT,
                            MiniHomeCacheConflictCategory.INVENTORY_APPLY,
                            conflict.first,
                            operands,
                        )
                    )
                    throw CoherentMiniHomeCacheConflict(
                        MiniHomeCacheConflictCategory.INVENTORY_APPLY,
                        conflict.first,
                        operands,
                    )
                }
                MiniHomeCacheConflictDiagnostics.observe(
                    cacheObservation(
                        MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                        account,
                        operationId,
                        inventoryApplied.diagnosticOutcome(),
                        operands =
                            inventorySuccessOperands(
                                beforeInventory,
                                inventoryApplied.current,
                                inventoryWrite,
                            ),
                    )
                )
                val coherent =
                    database.cacheDao().currentMiniHomeSnapshotCache(account.value)
                        ?: throw currentSnapshotConflict(account, operationId, null)
                if (!coherent.coherent) {
                    throw currentSnapshotConflict(account, operationId, coherent)
                }
                MiniHomeCacheConflictDiagnostics.observe(
                    cacheObservation(
                        MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                        account,
                        operationId,
                        MiniHomeCacheDiagnosticOutcome.VERIFIED,
                        operands = coherent.snapshotOperands(),
                    )
                )
                val inventory =
                    coherent.inventory?.verifiedAuthoritativeInventoryOrNull(account)
                        ?: throw verifiedInventoryConflict(account, operationId, coherent.inventory)
                MiniHomeCacheConflictDiagnostics.observe(
                    cacheObservation(
                        MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                        account,
                        operationId,
                        MiniHomeCacheDiagnosticOutcome.VERIFIED,
                        operands = coherent.inventory.decodeOperands(account),
                    )
                )
                CoherentMiniHomeCacheApply.Current(
                    requireNotNull(coherent.layout),
                    inventory,
                )
            }
        } catch (conflict: CoherentMiniHomeCacheConflict) {
            MiniHomeCacheConflictDiagnostics.observe(
                cacheObservation(
                    MiniHomeCacheDiagnosticStage.TERMINAL_CONFLICT,
                    account,
                    operationId,
                    MiniHomeCacheDiagnosticOutcome.CONFLICT,
                    conflict.category,
                    conflict.predicate,
                    conflict.operands,
                )
            )
            CoherentMiniHomeCacheApply.Conflict
        }
    }

    private fun cacheObservation(
        stage: MiniHomeCacheDiagnosticStage,
        account: AccountId,
        operationId: OperationId?,
        outcome: MiniHomeCacheDiagnosticOutcome,
        category: MiniHomeCacheConflictCategory? = null,
        predicate: MiniHomeCacheConflictPredicate? = null,
        operands: Map<String, String?> = emptyMap(),
    ) =
        MiniHomeCacheDiagnosticObservation(
            stage,
            account,
            operationId,
            outcome,
            category,
            predicate,
            operands,
        )

    private fun MiniHomeCacheApplyResult.diagnosticOutcome() =
        when (this) {
            is MiniHomeCacheApplyResult.Applied -> MiniHomeCacheDiagnosticOutcome.APPLIED
            is MiniHomeCacheApplyResult.Ignored -> MiniHomeCacheDiagnosticOutcome.IGNORED
            is MiniHomeCacheApplyResult.Conflict -> MiniHomeCacheDiagnosticOutcome.CONFLICT
        }

    private fun InventoryCacheApplyResult.diagnosticOutcome() =
        when (this) {
            is InventoryCacheApplyResult.Applied -> MiniHomeCacheDiagnosticOutcome.APPLIED
            is InventoryCacheApplyResult.Ignored -> MiniHomeCacheDiagnosticOutcome.IGNORED
            is InventoryCacheApplyResult.Conflict -> MiniHomeCacheDiagnosticOutcome.CONFLICT
        }

    private fun cacheWatermarks(
        before: CachedMiniHomeLayoutState?,
        after: CachedMiniHomeLayoutState,
        write: AuthoritativeMiniHomeCacheWrite,
    ): Map<String, String?> =
        mapOf(
            "before.generation" to before?.watermark?.generation?.toString(),
            "before.kind" to before?.watermark?.kind?.name,
            "before.revision" to before?.watermark?.layoutRevision?.toString(),
            "before.operationId" to before?.watermark?.operationId,
            "before.payloadHash" to before?.watermark?.payloadHash,
            "before.snapshotToken" to before?.watermark?.snapshotToken,
            "before.snapshotGeneration" to before?.watermark?.snapshotGeneration?.toString(),
            "after.generation" to after.watermark.generation.toString(),
            "after.kind" to after.watermark.kind.name,
            "after.revision" to after.watermark.layoutRevision?.toString(),
            "after.operationId" to after.watermark.operationId,
            "after.payloadHash" to after.watermark.payloadHash,
            "after.snapshotToken" to after.watermark.snapshotToken,
            "after.snapshotGeneration" to after.watermark.snapshotGeneration?.toString(),
            "candidate.generation" to write.generation.toString(),
            "candidate.generationOrdering" to
                generationOrdering(write.generation, after.watermark.generation),
            "candidate.snapshotToken" to write.snapshotToken,
            "candidate.snapshotGeneration" to write.snapshotGeneration?.toString(),
        )

    private fun layoutSuccessOperands(
        before: CachedMiniHomeLayoutState?,
        after: CachedMiniHomeLayoutState,
        write: AuthoritativeMiniHomeCacheWrite,
    ): Map<String, String?> {
        val candidateWatermark = write.diagnosticWatermark()
        val candidateHome = (write as? AuthoritativeMiniHomeCacheWrite.Layout)?.home
        val candidatePlacements =
            (write as? AuthoritativeMiniHomeCacheWrite.Layout)?.placements.orEmpty()
        return layoutStateOperands("before", before) +
            layoutStateOperands("after", after) +
            layoutStateOperands(
                "candidate",
                candidateWatermark,
                candidateHome,
                candidatePlacements,
            ) +
            mapOf(
                "candidate.generationOrdering" to
                    generationOrdering(write.generation, before?.watermark?.generation ?: 0)
            )
    }

    private fun layoutStateOperands(
        prefix: String,
        state: CachedMiniHomeLayoutState?,
    ): Map<String, String?> =
        if (state == null) {
            layoutStateOperands(prefix, null, null, emptyList())
        } else {
            layoutStateOperands(prefix, state.watermark, state.home, state.placements)
        }

    private fun layoutStateOperands(
        prefix: String,
        watermark: com.planterior.helper.core.database.MiniHomeCacheWatermark?,
        home: CachedMiniHomeEntity?,
        placements: List<CachedMiniHomePlacementEntity>,
    ): Map<String, String?> =
        mapOf(
            "$prefix.present" to (watermark != null).toString(),
            "$prefix.accountId" to watermark?.accountId,
            "$prefix.generation" to watermark?.generation?.toString(),
            "$prefix.kind" to watermark?.kind?.name,
            "$prefix.layoutRevision" to watermark?.layoutRevision?.toString(),
            "$prefix.miniHomeId" to watermark?.miniHomeId,
            "$prefix.operationId" to watermark?.operationId,
            "$prefix.payloadHash" to watermark?.payloadHash,
            "$prefix.tombstoneId" to watermark?.tombstoneId,
            "$prefix.authoritativeAtEpochMillis" to
                watermark?.authoritativeAtEpochMillis?.toString(),
            "$prefix.verified" to watermark?.verified?.toString(),
            "$prefix.snapshotToken" to watermark?.snapshotToken,
            "$prefix.snapshotGeneration" to watermark?.snapshotGeneration?.toString(),
        ) + homeOperands("$prefix.home", home) + placementOperands("$prefix.placements", placements)

    private fun inventoryWatermarks(
        before: CachedInventoryState?,
        after: CachedInventoryState,
        write: AuthoritativeInventoryCacheWrite,
    ): Map<String, String?> =
        mapOf(
            "before.generation" to before?.watermark?.generation?.toString(),
            "before.snapshotHash" to before?.watermark?.snapshotHash,
            "before.registeredPlantCount" to before?.watermark?.registeredPlantCount?.toString(),
            "before.partial" to before?.watermark?.partial?.toString(),
            "before.snapshotToken" to before?.watermark?.snapshotToken,
            "before.snapshotGeneration" to before?.watermark?.snapshotGeneration?.toString(),
            "after.generation" to after.watermark.generation.toString(),
            "after.snapshotHash" to after.watermark.snapshotHash,
            "after.registeredPlantCount" to after.watermark.registeredPlantCount.toString(),
            "after.partial" to after.watermark.partial.toString(),
            "after.snapshotToken" to after.watermark.snapshotToken,
            "after.snapshotGeneration" to after.watermark.snapshotGeneration?.toString(),
            "candidate.generation" to write.generation.toString(),
            "candidate.generationOrdering" to
                generationOrdering(write.generation, after.watermark.generation),
            "candidate.snapshotHash" to write.snapshotHash,
            "candidate.registeredPlantCount" to write.registeredPlantCount.toString(),
            "candidate.partial" to write.partial.toString(),
            "candidate.snapshotToken" to write.snapshotToken,
            "candidate.snapshotGeneration" to write.snapshotGeneration?.toString(),
        )

    private fun inventorySuccessOperands(
        before: CachedInventoryState?,
        after: CachedInventoryState,
        write: AuthoritativeInventoryCacheWrite,
    ): Map<String, String?> =
        inventoryStateOperands("before", before) +
            inventoryStateOperands("after", after) +
            inventoryStateOperands("candidate", write) +
            mapOf(
                "candidate.generationOrdering" to
                    generationOrdering(write.generation, before?.watermark?.generation ?: 0)
            )

    private fun inventoryStateOperands(
        prefix: String,
        state: CachedInventoryState?,
    ): Map<String, String?> =
        mapOf(
            "$prefix.present" to (state != null).toString(),
            "$prefix.accountId" to state?.watermark?.accountId,
            "$prefix.generation" to state?.watermark?.generation?.toString(),
            "$prefix.snapshotHash" to state?.watermark?.snapshotHash,
            "$prefix.registeredPlantCount" to state?.watermark?.registeredPlantCount?.toString(),
            "$prefix.loadedAtEpochMillis" to state?.watermark?.loadedAtEpochMillis?.toString(),
            "$prefix.partial" to state?.watermark?.partial?.toString(),
            "$prefix.verified" to state?.watermark?.verified?.toString(),
            "$prefix.snapshotToken" to state?.watermark?.snapshotToken,
            "$prefix.snapshotGeneration" to state?.watermark?.snapshotGeneration?.toString(),
        ) +
            catalogOperands("$prefix.catalog", state?.catalog.orEmpty()) +
            ownedOperands("$prefix.owned", state?.owned.orEmpty())

    private fun inventoryStateOperands(
        prefix: String,
        write: AuthoritativeInventoryCacheWrite,
    ): Map<String, String?> =
        mapOf(
            "$prefix.present" to "true",
            "$prefix.accountId" to write.accountId,
            "$prefix.generation" to write.generation.toString(),
            "$prefix.snapshotHash" to write.snapshotHash,
            "$prefix.registeredPlantCount" to write.registeredPlantCount.toString(),
            "$prefix.loadedAtEpochMillis" to write.loadedAtEpochMillis.toString(),
            "$prefix.partial" to write.partial.toString(),
            "$prefix.verified" to "true",
            "$prefix.snapshotToken" to write.snapshotToken,
            "$prefix.snapshotGeneration" to write.snapshotGeneration?.toString(),
        ) +
            catalogOperands("$prefix.catalog", write.catalog) +
            ownedOperands("$prefix.owned", write.owned)

    private fun generationOrdering(candidate: Long, current: Long): String =
        when {
            candidate < current -> "candidate-lower"
            candidate == current -> "equal"
            else -> "candidate-higher"
        }

    private fun layoutConflict(
        write: AuthoritativeMiniHomeCacheWrite,
        current: CachedMiniHomeLayoutState,
    ): Pair<MiniHomeCacheConflictPredicate, Map<String, String?>> {
        val candidate = write.diagnosticWatermark()
        val candidateHome = (write as? AuthoritativeMiniHomeCacheWrite.Layout)?.home
        val candidatePlacements =
            (write as? AuthoritativeMiniHomeCacheWrite.Layout)?.placements.orEmpty()
        val operands =
            layoutWatermarkOperands(current.watermark, candidate) +
                homeOperands("current.home", current.home) +
                homeOperands("candidate.home", candidateHome) +
                placementOperands("current.placements", current.placements) +
                placementOperands("candidate.placements", candidatePlacements) +
                mapOf(
                    "coherence.current.token" to current.watermark.snapshotToken,
                    "coherence.current.generation" to
                        current.watermark.snapshotGeneration?.toString(),
                    "coherence.candidate.token" to candidate.snapshotToken,
                    "coherence.candidate.generation" to candidate.snapshotGeneration?.toString(),
                )
        return selectLayoutCacheConflictPredicate(operands) to operands
    }

    private fun AuthoritativeMiniHomeCacheWrite.diagnosticWatermark() =
        when (this) {
            is AuthoritativeMiniHomeCacheWrite.Layout ->
                com.planterior.helper.core.database.MiniHomeCacheWatermark(
                    accountId,
                    generation,
                    MiniHomeCacheWatermarkKind.PRESENT,
                    home.revision,
                    home.miniHomeId,
                    operationId,
                    payloadHash,
                    null,
                    authoritativeAtEpochMillis,
                    true,
                    snapshotToken,
                    snapshotGeneration,
                )
            is AuthoritativeMiniHomeCacheWrite.Deletion ->
                com.planterior.helper.core.database.MiniHomeCacheWatermark(
                    accountId,
                    generation,
                    MiniHomeCacheWatermarkKind.DELETED,
                    null,
                    null,
                    null,
                    null,
                    tombstoneId,
                    authoritativeAtEpochMillis,
                    true,
                    snapshotToken,
                    snapshotGeneration,
                )
        }

    private fun inventoryConflict(
        write: AuthoritativeInventoryCacheWrite,
        current: CachedInventoryState,
    ): Pair<MiniHomeCacheConflictPredicate, Map<String, String?>> {
        val operands =
            inventoryIdentityOperands(current, write) +
                catalogOperands("current.catalog", current.catalog) +
                catalogOperands("candidate.catalog", write.catalog) +
                ownedOperands("current.owned", current.owned) +
                ownedOperands("candidate.owned", write.owned)
        return selectInventoryCacheConflictPredicate(operands) to operands
    }

    private fun layoutWatermarkOperands(
        current: com.planterior.helper.core.database.MiniHomeCacheWatermark,
        candidate: com.planterior.helper.core.database.MiniHomeCacheWatermark,
    ): Map<String, String?> =
        mapOf(
            "watermark.current.accountId" to current.accountId,
            "watermark.candidate.accountId" to candidate.accountId,
            "watermark.current.generation" to current.generation.toString(),
            "watermark.candidate.generation" to candidate.generation.toString(),
            "watermark.generationOrdering" to
                generationOrdering(candidate.generation, current.generation),
            "watermark.current.kind" to current.kind.name,
            "watermark.candidate.kind" to candidate.kind.name,
            "watermark.current.layoutRevision" to current.layoutRevision?.toString(),
            "watermark.candidate.layoutRevision" to candidate.layoutRevision?.toString(),
            "watermark.current.miniHomeId" to current.miniHomeId,
            "watermark.candidate.miniHomeId" to candidate.miniHomeId,
            "watermark.current.operationId" to current.operationId,
            "watermark.candidate.operationId" to candidate.operationId,
            "watermark.current.payloadHash" to current.payloadHash,
            "watermark.candidate.payloadHash" to candidate.payloadHash,
            "watermark.current.tombstoneId" to current.tombstoneId,
            "watermark.candidate.tombstoneId" to candidate.tombstoneId,
            "watermark.current.verified" to current.verified.toString(),
            "watermark.candidate.verified" to candidate.verified.toString(),
        )

    private fun homeOperands(
        prefix: String,
        home: CachedMiniHomeEntity?,
    ): Map<String, String?> =
        mapOf(
            "$prefix.present" to (home != null).toString(),
            "$prefix.accountId" to home?.accountId,
            "$prefix.miniHomeId" to home?.miniHomeId,
            "$prefix.name" to home?.name,
            "$prefix.placedPlantCount" to home?.placedPlantCount?.toString(),
            "$prefix.revision" to home?.revision?.toString(),
            "$prefix.updatedAtEpochMillis" to home?.updatedAtEpochMillis?.toString(),
        )

    private fun placementOperands(
        prefix: String,
        placements: List<CachedMiniHomePlacementEntity>,
    ): Map<String, String?> = buildMap {
        put("$prefix.count", placements.size.toString())
        put("$prefix.order", placements.joinToString(",") { it.placementId })
        placements
            .sortedWith(
                compareBy<CachedMiniHomePlacementEntity> { it.zIndex }.thenBy { it.placementId }
            )
            .forEachIndexed { index, placement ->
                val item = "$prefix.$index"
                put("$item.accountId", placement.accountId)
                put("$item.placementId", placement.placementId)
                put("$item.miniHomeId", placement.miniHomeId)
                put("$item.plantId", placement.plantId)
                put("$item.itemId", placement.itemId)
                put("$item.normalizedX", placement.normalizedX.toString())
                put("$item.normalizedY", placement.normalizedY.toString())
                put("$item.zIndex", placement.zIndex.toString())
                put("$item.layoutRevision", placement.layoutRevision.toString())
            }
    }

    private fun inventoryIdentityOperands(
        current: CachedInventoryState,
        candidate: AuthoritativeInventoryCacheWrite,
    ): Map<String, String?> =
        mapOf(
            "inventory.current.accountId" to current.watermark.accountId,
            "inventory.candidate.accountId" to candidate.accountId,
            "inventory.current.generation" to current.watermark.generation.toString(),
            "inventory.candidate.generation" to candidate.generation.toString(),
            "inventory.generationOrdering" to
                generationOrdering(candidate.generation, current.watermark.generation),
            "inventory.current.snapshotHash" to current.watermark.snapshotHash,
            "inventory.candidate.snapshotHash" to candidate.snapshotHash,
            "inventory.current.registeredPlantCount" to
                current.watermark.registeredPlantCount.toString(),
            "inventory.candidate.registeredPlantCount" to candidate.registeredPlantCount.toString(),
            "inventory.current.partial" to current.watermark.partial.toString(),
            "inventory.candidate.partial" to candidate.partial.toString(),
            "inventory.current.snapshotToken" to current.watermark.snapshotToken,
            "inventory.candidate.snapshotToken" to candidate.snapshotToken,
            "inventory.current.snapshotGeneration" to
                current.watermark.snapshotGeneration?.toString(),
            "inventory.candidate.snapshotGeneration" to candidate.snapshotGeneration?.toString(),
        )

    private fun catalogOperands(
        prefix: String,
        catalog: List<CachedShopItemEntity>,
    ): Map<String, String?> = buildMap {
        put("$prefix.count", catalog.size.toString())
        catalog
            .sortedBy { it.itemId }
            .forEachIndexed { index, item ->
                val key = "$prefix.$index"
                put("$key.accountId", item.accountId)
                put("$key.itemId", item.itemId)
                put("$key.name", item.name)
                put("$key.description", item.description)
                put("$key.category", item.category)
                put("$key.assetPath", item.assetPath)
                put("$key.acquisitionCondition", item.acquisitionCondition)
                put("$key.revision", item.revision.toString())
                put("$key.updatedAtEpochMillis", item.updatedAtEpochMillis.toString())
                put("$key.assetSha256", item.assetSha256)
                put("$key.assetByteSize", item.assetByteSize.toString())
                put("$key.assetMimeType", item.assetMimeType)
                put("$key.assetWidth", item.assetWidth.toString())
                put("$key.assetHeight", item.assetHeight.toString())
                put("$key.assetMediaRevision", item.assetMediaRevision.toString())
            }
    }

    private fun ownedOperands(
        prefix: String,
        owned: List<CachedOwnedItemEntity>,
    ): Map<String, String?> = buildMap {
        put("$prefix.count", owned.size.toString())
        owned
            .sortedBy { it.itemId }
            .forEachIndexed { index, item ->
                val key = "$prefix.$index"
                put("$key.accountId", item.accountId)
                put("$key.itemId", item.itemId)
                put("$key.acquiredAtEpochMillis", item.acquiredAtEpochMillis.toString())
                put("$key.applied", item.applied.toString())
                put("$key.revision", item.revision.toString())
                put("$key.availability", item.availability)
                put("$key.nameSnapshot", item.nameSnapshot)
                put("$key.categorySnapshot", item.categorySnapshot)
                put("$key.assetPathSnapshot", item.assetPathSnapshot)
                put("$key.catalogRevisionSnapshot", item.catalogRevisionSnapshot?.toString())
                put("$key.assetSha256Snapshot", item.assetSha256Snapshot)
                put("$key.assetByteSizeSnapshot", item.assetByteSizeSnapshot?.toString())
                put("$key.assetMimeTypeSnapshot", item.assetMimeTypeSnapshot)
                put("$key.assetWidthSnapshot", item.assetWidthSnapshot?.toString())
                put("$key.assetHeightSnapshot", item.assetHeightSnapshot?.toString())
                put("$key.assetMediaRevisionSnapshot", item.assetMediaRevisionSnapshot?.toString())
            }
    }

    private fun currentSnapshotConflict(
        account: AccountId,
        operationId: OperationId?,
        current: CachedMiniHomeSnapshotState?,
    ): CoherentMiniHomeCacheConflict {
        val operands = current.snapshotOperands()
        val predicate = selectCurrentSnapshotConflictPredicate(operands)
        MiniHomeCacheConflictDiagnostics.observe(
            cacheObservation(
                MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                account,
                operationId,
                MiniHomeCacheDiagnosticOutcome.CONFLICT,
                MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT,
                predicate,
                operands,
            )
        )
        return CoherentMiniHomeCacheConflict(
            MiniHomeCacheConflictCategory.CURRENT_SNAPSHOT,
            predicate,
            operands,
        )
    }

    private fun CachedMiniHomeSnapshotState?.snapshotOperands(): Map<String, String?> =
        mapOf(
            "snapshot.present" to (this != null).toString(),
            "layout.present" to (this?.layout != null).toString(),
            "inventory.present" to (this?.inventory != null).toString(),
            "layout.accountId" to this?.layout?.watermark?.accountId,
            "inventory.accountId" to this?.inventory?.watermark?.accountId,
            "layout.verified" to this?.layout?.watermark?.verified?.toString(),
            "inventory.verified" to this?.inventory?.watermark?.verified?.toString(),
            "layout.token" to this?.layout?.watermark?.snapshotToken,
            "inventory.token" to this?.inventory?.watermark?.snapshotToken,
            "layout.generation" to this?.layout?.watermark?.snapshotGeneration?.toString(),
            "inventory.generation" to this?.inventory?.watermark?.snapshotGeneration?.toString(),
            "layout.homePresent" to (this?.layout?.home != null).toString(),
            "layout.placementCount" to this?.layout?.placements?.size?.toString(),
            "inventory.catalogCount" to this?.inventory?.catalog?.size?.toString(),
            "inventory.ownedCount" to this?.inventory?.owned?.size?.toString(),
        )

    private fun verifiedInventoryConflict(
        account: AccountId,
        operationId: OperationId?,
        current: CachedInventoryState?,
    ): CoherentMiniHomeCacheConflict {
        val operands = current.decodeOperands(account)
        MiniHomeCacheConflictDiagnostics.observe(
            cacheObservation(
                MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                account,
                operationId,
                MiniHomeCacheDiagnosticOutcome.CONFLICT,
                MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
                MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD,
                operands,
            )
        )
        return CoherentMiniHomeCacheConflict(
            MiniHomeCacheConflictCategory.VERIFIED_INVENTORY_DECODE,
            MiniHomeCacheConflictPredicate.VERIFIED_INVENTORY_DECODE_FIELD,
            operands,
        )
    }

    private fun CachedInventoryState?.decodeOperands(account: AccountId): Map<String, String?> =
        buildMap {
            val failure = decodeFailure(account)
            put("field", failure.field)
            put("failure.index", failure.index?.toString())
            put("content.expectedSnapshotHash", failure.expectedSnapshotHash)
            put("expected.account", account.value)
            put("actual.account", this@decodeOperands?.watermark?.accountId)
            put("verified", this@decodeOperands?.watermark?.verified?.toString())
            put("generation", this@decodeOperands?.watermark?.generation?.toString())
            put("snapshotHash", this@decodeOperands?.watermark?.snapshotHash)
            put(
                "registeredPlantCount",
                this@decodeOperands?.watermark?.registeredPlantCount?.toString(),
            )
            put(
                "loadedAtEpochMillis",
                this@decodeOperands?.watermark?.loadedAtEpochMillis?.toString(),
            )
            put("partial", this@decodeOperands?.watermark?.partial?.toString())
            put("snapshotToken", this@decodeOperands?.watermark?.snapshotToken)
            put(
                "snapshotGeneration",
                this@decodeOperands?.watermark?.snapshotGeneration?.toString(),
            )
            putAll(catalogOperands("catalog", this@decodeOperands?.catalog.orEmpty()))
            putAll(ownedOperands("owned", this@decodeOperands?.owned.orEmpty()))
        }

    private data class InventoryDecodeFailure(
        val field: String?,
        val index: Int? = null,
        val expectedSnapshotHash: String? = null,
    )

    private fun CachedInventoryState?.decodeFailure(account: AccountId): InventoryDecodeFailure {
        if (this == null) return InventoryDecodeFailure("inventory")
        if (watermark.accountId != account.value) {
            return InventoryDecodeFailure("watermark.accountId")
        }
        if (!watermark.verified) return InventoryDecodeFailure("watermark.verified")
        if (watermark.generation < 1) return InventoryDecodeFailure("watermark.generation")
        if (!watermark.snapshotHash.matches(Regex("^[a-f0-9]{64}$"))) {
            return InventoryDecodeFailure("watermark.snapshotHash.format")
        }
        if (watermark.registeredPlantCount !in 0..200) {
            return InventoryDecodeFailure("watermark.registeredPlantCount")
        }
        if (watermark.loadedAtEpochMillis !in 0..9_007_199_254_740_991L) {
            return InventoryDecodeFailure("watermark.loadedAtEpochMillis")
        }
        if (catalog.size > 200) return InventoryDecodeFailure("catalog.count")
        if (owned.size > 200) return InventoryDecodeFailure("owned.count")
        val decodedCatalog = mutableListOf<AuthoritativeCatalogItem>()
        catalog.forEachIndexed { index, entity ->
            val decoded = runCatching { entity.diagnosticAuthoritative(account) }.getOrNull()
            if (decoded == null) return InventoryDecodeFailure("catalog.entity", index)
            decodedCatalog += decoded
        }
        val decodedOwned = mutableListOf<AuthoritativeOwnedItem>()
        owned.forEachIndexed { index, entity ->
            val decoded = runCatching { entity.diagnosticAuthoritative(account) }.getOrNull()
            if (decoded == null) return InventoryDecodeFailure("owned.entity", index)
            decodedOwned += decoded
        }
        val duplicateCatalogIndex = decodedCatalog.indexOfFirst { item ->
            decodedCatalog.count { it.itemId == item.itemId } > 1
        }
        if (duplicateCatalogIndex >= 0) {
            return InventoryDecodeFailure("catalog.duplicateItemId", duplicateCatalogIndex)
        }
        val duplicateOwnedIndex = decodedOwned.indexOfFirst { item ->
            decodedOwned.count { it.itemId == item.itemId } > 1
        }
        if (duplicateOwnedIndex >= 0) {
            return InventoryDecodeFailure("owned.duplicateItemId", duplicateOwnedIndex)
        }
        val catalogIds = decodedCatalog.mapTo(mutableSetOf()) { it.itemId }
        val availabilityIndex = decodedOwned.indexOfFirst {
            (it.availability == AuthoritativeInventoryAvailability.AVAILABLE) !=
                (it.itemId in catalogIds)
        }
        if (availabilityIndex >= 0) {
            return InventoryDecodeFailure("owned.catalogAvailability", availabilityIndex)
        }
        val unavailableIndex = decodedOwned.indexOfFirst {
            !watermark.partial && it.availability == AuthoritativeInventoryAvailability.UNAVAILABLE
        }
        if (unavailableIndex >= 0) {
            return InventoryDecodeFailure("partial.unavailableOwned", unavailableIndex)
        }
        val expectedHash =
            authoritativeInventorySnapshotHash(
                account,
                decodedCatalog,
                decodedOwned,
                watermark.registeredPlantCount,
                watermark.partial,
            )
        return if (expectedHash != watermark.snapshotHash) {
            InventoryDecodeFailure(
                "watermark.snapshotHash.content",
                expectedSnapshotHash = expectedHash,
            )
        } else {
            InventoryDecodeFailure(null, expectedSnapshotHash = expectedHash)
        }
    }

    private fun CachedShopItemEntity.diagnosticAuthoritative(
        owner: AccountId
    ): AuthoritativeCatalogItem {
        require(accountId == owner.value)
        require(name.codePointCount(0, name.length) in 1..100)
        require(description.codePointCount(0, description.length) in 1..500)
        val typedItemId = ItemId(itemId)
        val identity =
            CatalogMediaIdentity(
                assetPath,
                assetSha256,
                assetByteSize,
                assetMimeType,
                assetWidth,
                assetHeight,
                Revision(assetMediaRevision),
            )
        require(identity.path.startsWith("catalog-assets/${typedItemId.value}/"))
        return AuthoritativeCatalogItem(
            typedItemId,
            name,
            description,
            ItemCategory.valueOf(category),
            identity,
            acquisitionCondition?.let { value ->
                AuthoritativeInventoryCondition.entries.single { it.wireValue == value }
            },
            Revision(revision).also { require(it.value >= 1) },
            updatedAtEpochMillis.also { require(it in 0..9_007_199_254_740_991L) },
        )
    }

    private fun CachedOwnedItemEntity.diagnosticAuthoritative(
        owner: AccountId
    ): AuthoritativeOwnedItem {
        require(accountId == owner.value)
        val snapshotValues =
            listOf(
                nameSnapshot,
                categorySnapshot,
                assetPathSnapshot,
                catalogRevisionSnapshot,
                assetSha256Snapshot,
                assetByteSizeSnapshot,
                assetMimeTypeSnapshot,
                assetWidthSnapshot,
                assetHeightSnapshot,
                assetMediaRevisionSnapshot,
            )
        require(snapshotValues.all { it == null } || snapshotValues.none { it == null })
        val snapshot =
            if (snapshotValues.all { it == null }) {
                null
            } else {
                val snapshotName = requireNotNull(nameSnapshot)
                require(snapshotName.codePointCount(0, snapshotName.length) in 1..100)
                val identity =
                    CatalogMediaIdentity(
                        requireNotNull(assetPathSnapshot),
                        requireNotNull(assetSha256Snapshot),
                        requireNotNull(assetByteSizeSnapshot),
                        requireNotNull(assetMimeTypeSnapshot),
                        requireNotNull(assetWidthSnapshot),
                        requireNotNull(assetHeightSnapshot),
                        Revision(requireNotNull(assetMediaRevisionSnapshot)),
                    )
                require(identity.path.startsWith("catalog-assets/$itemId/"))
                AuthoritativeOwnedCatalogSnapshot(
                    snapshotName,
                    ItemCategory.valueOf(requireNotNull(categorySnapshot)),
                    identity,
                    Revision(requireNotNull(catalogRevisionSnapshot)).also {
                        require(it.value >= 1)
                    },
                )
            }
        return AuthoritativeOwnedItem(
            ItemId(itemId),
            acquiredAtEpochMillis.also { require(it in 0..9_007_199_254_740_991L) },
            applied,
            Revision(revision).also { require(it.value >= 1) },
            AuthoritativeInventoryAvailability.valueOf(availability),
            snapshot,
        )
    }

    private fun RemoteMiniHomeSnapshot.fromCache(
        current: CachedMiniHomeLayoutState,
        inventory: AuthoritativeInventory,
    ): RemoteMiniHomeSnapshot {
        val layout = current.layoutOrNull()
        val watermark = current.watermark
        return copy(
            layout = layout,
            decorations = inventory.miniHomeDecorationChoices(),
            committedOperationId =
                watermark.operationId?.let(::OperationId).takeIf {
                    watermark.kind == MiniHomeCacheWatermarkKind.PRESENT
                },
            committedExpectedRevision = layout?.revision?.value?.minus(1)?.let(::Revision),
            committedPayloadHash = watermark.payloadHash,
            cacheGeneration = watermark.generation,
            cacheOperationId = watermark.operationId,
            cachePayloadHash = watermark.payloadHash,
            deletionTombstoneId = watermark.tombstoneId,
            authoritativeAtEpochMillis = watermark.authoritativeAtEpochMillis,
            authoritativeInventory = inventory,
        )
    }

    private fun CachedMiniHomeLayoutState.layoutOrNull(): MiniHomeLayout? {
        val cached = home ?: return null
        return MiniHomeLayout(
            MiniHomeId(cached.miniHomeId),
            cached.name,
            MiniHomePlacementPolicy.layer(placements.map { it.placement() }),
            Revision(cached.revision),
            Instant.ofEpochMilli(cached.updatedAtEpochMillis),
        )
    }

    private suspend fun beginPublication(account: AccountId): MiniHomePublicationToken? =
        publicationMutex.withLock {
            observePublicationOwner(remote.activeAccount())
            if (observedPublicationOwner != account) return@withLock null
            requestGeneration += 1
            currentOwnerRequest[account.value] = requestGeneration
            MiniHomePublicationToken(account, ownerGeneration, requestGeneration)
        }

    private fun observePublicationOwner(active: AccountId?) {
        if (observedPublicationOwner != active) {
            observedPublicationOwner = active
            ownerGeneration += 1
        }
    }

    private suspend fun publicationDecision(
        token: MiniHomePublicationToken,
        version: MiniHomePublicationVersion,
    ): MiniHomePublicationDecision = publicationMutex.withLock {
        observePublicationOwner(remote.activeAccount())
        if (
            observedPublicationOwner != token.accountId ||
                ownerGeneration != token.ownerGeneration ||
                currentOwnerRequest[token.accountId.value] != token.requestGeneration
        ) {
            return@withLock MiniHomePublicationDecision.FORBIDDEN
        }
        val previous = lastPublished[token.accountId.value]
        if (previous != null && !version.dominates(previous)) {
            return@withLock MiniHomePublicationDecision.REREAD
        }
        lastPublished[token.accountId.value] = version
        MiniHomePublicationDecision.PUBLISH
    }

    private suspend fun publishCurrentRoomWinner(
        account: AccountId,
        token: MiniHomePublicationToken,
        stale: Boolean,
        fresh: RemoteMiniHomeSnapshot? = null,
        pendingBeforeLoad: OperationOutboxEntity? = null,
        loadId: MiniHomeLoadIdentity? = null,
    ): MiniHomeLoadResult {
        var reconciledPending: MiniHomePendingSave? = null
        var pendingWasReconciled = false
        repeat(MAX_PUBLICATION_REREADS) {
            if (remote.activeAccount() != account) return MiniHomeLoadResult.Forbidden
            val current =
                try {
                    readCoherentCachedPublication(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return MiniHomeLoadResult.Failed
                }
            if (current.incoherentSnapshot) {
                try {
                    database.cacheDao().purgeIncoherentMiniHomeSnapshot(account.value)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The token-mismatched pair is never published; cleanup retries later.
                }
                return MiniHomeLoadResult.Failed
            }
            current.corruptInventoryGeneration?.let { (generation, snapshotHash) ->
                try {
                    database
                        .cacheDao()
                        .purgeInventoryCacheIfMatches(account.value, generation, snapshotHash)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The invalid snapshot is not published; a later load retries cleanup.
                }
                return MiniHomeLoadResult.Failed
            }
            val recovered = current.recovery
            if (recovered is CachedMiniHomeRecovery.Irrecoverable) {
                try {
                    database.cacheDao().quarantineMiniHome(account.value)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The invalid layout is not published; a later load retries cleanup.
                }
                return MiniHomeLoadResult.Failed
            }
            if (recovered is CachedMiniHomeRecovery.Ready && recovered.legacyName != null) {
                try {
                    database
                        .cacheDao()
                        .rewriteLegacyMiniHomeName(
                            account.value,
                            recovered.layout.id.value,
                            recovered.layout.revision.value,
                            recovered.legacyName,
                            recovered.layout.name,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The canonical value remains safe and the next load retries persistence.
                }
            }
            val effective = fresh?.let { source ->
                val state = current.layoutState ?: return MiniHomeLoadResult.Failed
                val inventory = current.inventory ?: return MiniHomeLoadResult.Failed
                source.fromCache(state, inventory)
            }
            if (effective != null && !pendingWasReconciled) {
                reconciledPending =
                    reconcilePendingOnLoad(account, effective, pendingBeforeLoad, loadId)
                pendingWasReconciled = true
            }
            val pending = if (pendingWasReconciled) reconciledPending else pending(account, loadId)
            val stable =
                try {
                    readCoherentCachedPublication(account)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return MiniHomeLoadResult.Failed
                }
            if (stable != current) return@repeat
            val layout = (recovered as? CachedMiniHomeRecovery.Ready)?.layout ?: blankLayout()
            val decorations =
                current.inventory
                    ?.miniHomeDecorationChoices()
                    ?.map { choice ->
                        if (stale) choice.copy(availableForApplication = false) else choice
                    }
                    .orEmpty()
            val receipt = effective?.committedReceiptOrNull()
            val ready =
                MiniHomeLoadResult.Ready(
                    account,
                    layout,
                    effective?.plants ?: current.plants,
                    decorations,
                    stale,
                    pending,
                    receipt,
                    loadIdentity = loadId,
                )
            when (publicationDecision(token, current.version)) {
                MiniHomePublicationDecision.PUBLISH -> return ready
                MiniHomePublicationDecision.FORBIDDEN -> return MiniHomeLoadResult.Forbidden
                MiniHomePublicationDecision.REREAD -> Unit
            }
        }
        return if (remote.activeAccount() == account) {
            MiniHomeLoadResult.Failed
        } else {
            MiniHomeLoadResult.Forbidden
        }
    }

    private suspend fun readCoherentCachedPublication(
        account: AccountId
    ): CachedMiniHomePublication {
        val readIdentity =
            MiniHomePublicationReadIdentity(publicationReadSequence.incrementAndGet())
        observePublicationRead { beforePublicationRead(account, readIdentity) }
        val raw = database.withTransaction {
            database.cacheDao().currentMiniHomeSnapshotCache(account.value) to
                database.cacheDao().plants(account.value)
        }
        notifyPublicationReadReturned(account, readIdentity)
        val snapshotState = raw.first
        val incoherentSnapshot = snapshotState?.coherent == false
        val inventoryState = snapshotState?.inventory
        val inventory =
            inventoryState
                ?.takeIf { !incoherentSnapshot }
                ?.verifiedAuthoritativeInventoryOrNull(account)
        val corruptInventory =
            inventoryState
                ?.takeIf { !incoherentSnapshot && inventory == null }
                ?.watermark
                ?.let { it.generation to it.snapshotHash }
        val layoutState = snapshotState?.layout?.takeIf { !incoherentSnapshot }
        val watermark = layoutState?.watermark
        return CachedMiniHomePublication(
            layoutState = layoutState,
            recovery = recoverCachedLayout(account, layoutState),
            inventory = inventory,
            plants =
                raw.second.map {
                    MiniHomePlantChoice(
                        PersonalPlantId(it.plantId),
                        it.displayName,
                        it.representativePhotoPath,
                    )
                },
            version =
                MiniHomePublicationVersion(
                    snapshotGeneration = watermark?.takeIf { it.verified }?.snapshotGeneration ?: 0,
                    layoutGeneration = watermark?.takeIf { it.verified }?.generation ?: 0,
                    layoutRevision = watermark?.takeIf { it.verified }?.layoutRevision,
                    layoutKind = watermark?.takeIf { it.verified }?.kind,
                    inventoryGeneration = inventory?.generation ?: 0,
                ),
            corruptInventoryGeneration = corruptInventory,
            incoherentSnapshot = incoherentSnapshot,
        )
    }

    private suspend fun notifyPublicationReadReturned(
        account: AccountId,
        readIdentity: MiniHomePublicationReadIdentity,
    ) = observePublicationRead { afterPublicationRead(account, readIdentity) }

    private suspend fun observePublicationRead(observe: suspend () -> Unit) {
        try {
            observe()
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: AssertionError) {
            return
        } catch (_: Exception) {
            return
        }
    }

    private fun recoverCachedLayout(
        account: AccountId,
        state: CachedMiniHomeLayoutState?,
    ): CachedMiniHomeRecovery {
        if (state == null) return CachedMiniHomeRecovery.Missing
        return runCatching {
            val watermark = state.watermark
            require(watermark.accountId == account.value)
            if (
                watermark.verified &&
                    (watermark.kind == MiniHomeCacheWatermarkKind.DELETED ||
                        watermark.kind == MiniHomeCacheWatermarkKind.CONVERGED_ABSENCE)
            ) {
                require(state.home == null && state.placements.isEmpty())
                return@runCatching CachedMiniHomeRecovery.Missing
            }
            val home = requireNotNull(state.home)
            require(home.accountId == account.value)
            if (watermark.verified) {
                require(watermark.kind == MiniHomeCacheWatermarkKind.PRESENT)
                require(watermark.layoutRevision == home.revision)
                require(watermark.miniHomeId == home.miniHomeId)
                require(watermark.operationId?.matches(Regex("^[A-Za-z0-9_-]{8,128}$")) == true)
                require(watermark.payloadHash?.matches(Regex("^[a-f0-9]{64}$")) == true)
            }
            val canonicalName = requireNotNull(recoverLegacyMiniHomeName(home.name))
            val persistedPlacements = state.placements.map { it.placement() }
            require(
                persistedPlacements.count { it.target is MiniHomePlacementTarget.Plant } ==
                    home.placedPlantCount
            )
            val placements =
                if (watermark.verified) {
                    require(MiniHomePlacementPolicy.isValid(persistedPlacements))
                    persistedPlacements
                } else {
                    MiniHomePlacementPolicy.layer(persistedPlacements)
                }
            CachedMiniHomeRecovery.Ready(
                MiniHomeLayout(
                    MiniHomeId(home.miniHomeId),
                    canonicalName,
                    placements,
                    Revision(home.revision),
                    Instant.ofEpochMilli(home.updatedAtEpochMillis),
                ),
                home.name.takeIf { it != canonicalName },
            )
        }
            .getOrElse { CachedMiniHomeRecovery.Irrecoverable }
    }

    private fun RemoteMiniHomeSnapshot.committedReceiptOrNull(): MiniHomeCommittedReceipt? {
        val operationId = committedOperationId ?: return null
        val expectedRevision = committedExpectedRevision ?: return null
        val committedRevision = layout?.revision ?: return null
        val payloadHash = committedPayloadHash ?: return null
        return runCatching {
            MiniHomeCommittedReceipt(
                operationId,
                expectedRevision,
                committedRevision,
                payloadHash,
            )
        }
            .getOrNull()
    }

    private fun RemoteMiniHomeSnapshot.recoverLegacyName(): RemoteMiniHomeSnapshot {
        val authoritative = layout ?: return this
        val canonicalName =
            recoverLegacyMiniHomeName(authoritative.name)
                ?: throw IllegalArgumentException("Stored mini-home name is irrecoverable")
        return copy(layout = authoritative.copy(name = canonicalName))
    }

    private suspend fun reconcilePendingOnLoad(
        account: AccountId,
        snapshot: RemoteMiniHomeSnapshot,
        pendingBeforeLoad: OperationOutboxEntity?,
        loadId: MiniHomeLoadIdentity?,
    ): MiniHomePendingSave? {
        var operation = pendingBeforeLoad ?: return pending(account, loadId)
        val decoded = decodePersistedOperation(operation)
        if (decoded is PersistedMiniHomeOperationDecode.Quarantined) {
            markReconciliationRequired(
                operation,
                decoded.failure.name,
                decoded.details,
                snapshot,
            ) ?: return pendingSnapshot(account, loadId)
            return pending(account, loadId)
        }
        decoded as PersistedMiniHomeOperationDecode.Canonical
        val restored = decoded.draft
        operation =
            ensurePayloadHash(operation, decoded.exactPayloadHash)
                ?: return pendingSnapshot(account, loadId)
        if (operation.receiptMatches(snapshot, decoded, decoded.exactPayloadHash)) {
            operation =
                persistAuthoritativeReceipt(operation, snapshot)
                    ?: return pendingSnapshot(account, loadId)
            return when (consume(operation)) {
                MiniHomeDiscardResult.Consumed,
                is MiniHomeDiscardResult.Committed,
                MiniHomeDiscardResult.Missing -> null
                is MiniHomeDiscardResult.StaleHandle,
                MiniHomeDiscardResult.OwnerMismatch,
                MiniHomeDiscardResult.Rejected -> pendingSnapshot(account, loadId)
            }
        }
        val transition =
            MiniHomeOutboxTransitionTable.transition(operation.state, operation.lastErrorCode)
        if (transition.requiresCorrection) return pending(account, loadId)
        if (transition.requiresExplicitReconciliation) {
            persistAuthoritativeReceipt(operation, snapshot)
                ?: return pendingSnapshot(account, loadId)
            return pending(account, loadId)
        }
        val authoritative = snapshot.layout ?: blankLayout()
        val mismatch =
            when {
                decoded.envelope.requiresCanonicalization ->
                    snapshot.mismatchReason(restored, decoded.exactPayloadHash) to
                        mismatchDetails(operation, snapshot)
                snapshot.committedOperationId == restored.operationId ->
                    snapshot.mismatchReason(restored, decoded.exactPayloadHash) to
                        mismatchDetails(operation, snapshot)
                authoritative.revision != restored.expectedRevision ->
                    MiniHomeSaveFailure.REVISION_CONFLICT to mismatchDetails(operation, snapshot)
                else -> null
            }
        if (mismatch != null) {
            markReconciliationRequired(
                operation,
                mismatch.first.name,
                mismatch.second,
                snapshot,
            ) ?: return pendingSnapshot(account, loadId)
        } else if (operation.state == PHASE_MAY_HAVE_COMMITTED) {
            compareAndSet(operation) {
                it.copy(
                    state = "PENDING",
                    actualRevision = null,
                    committedOperationId = null,
                    committedExpectedRevision = null,
                    committedRevision = null,
                    committedPayloadHash = null,
                )
            } ?: return pendingSnapshot(account, loadId)
        }
        return pending(account, loadId)
    }

    private suspend fun activePendingOperation(
        account: AccountId,
        loadId: MiniHomeLoadIdentity? = null,
    ): OperationOutboxEntity? {
        val operations =
            pendingOperations(account, loadId).filter { it.aggregateType == OUTBOX_TYPE }
        val superseded = operations.mapNotNullTo(mutableSetOf()) { it.supersedesOperationId }
        return operations
            .filterNot { it.operationId in superseded }
            .maxWithOrNull(
                compareBy<OperationOutboxEntity> { it.createdAtEpochMillis }
                    .thenBy {
                        it.operationId
                    }
            )
    }

    private suspend fun pendingOperations(
        account: AccountId,
        loadId: MiniHomeLoadIdentity?,
    ): List<OperationOutboxEntity> {
        if (loadId == null) return database.syncDao().pending(account.value)
        val identity =
            MiniHomePendingReadIdentity(
                loadId = loadId,
                queryOrdinal = pendingReadSequence.incrementAndGet(),
            )
        observePendingReadDiagnostic { beforePendingRead(account, identity) }
        return try {
            database.syncDao().pending(account.value).also {
                observePendingReadDiagnostic {
                    afterPendingRead(account, identity, MiniHomePendingReadOutcome.Returned)
                }
            }
        } catch (failure: Throwable) {
            val outcome =
                if (failure is CancellationException) {
                    MiniHomePendingReadOutcome.Cancelled(failure)
                } else {
                    MiniHomePendingReadOutcome.Threw(failure)
                }
            observePendingReadDiagnostic { afterPendingRead(account, identity, outcome) }
            throw failure
        }
    }

    private suspend fun observePendingReadDiagnostic(observe: suspend () -> Unit) {
        try {
            observe()
        } catch (_: AssertionError) {
            return
        } catch (_: Exception) {
            return
        }
    }

    private suspend fun pending(
        account: AccountId,
        loadId: MiniHomeLoadIdentity? = null,
    ): MiniHomePendingSave? {
        repeat(MAX_PENDING_CAS_ATTEMPTS) {
            val operation = activePendingOperation(account, loadId) ?: return null
            when (val decoded = decodePersistedOperation(operation)) {
                is PersistedMiniHomeOperationDecode.Canonical -> {
                    if (
                        decoded.envelope.requiresCanonicalization &&
                            operation.state != PHASE_RECONCILIATION_REQUIRED
                    ) {
                        if (
                            markEnvelopeReconciliationRequired(
                                operation,
                                MiniHomeSaveFailure.OUTBOX_MISMATCH,
                                "legacy raw payload requires canonical display recovery",
                            ) == null
                        ) {
                            return@repeat
                        }
                        return@repeat
                    }
                    return operation.pending(decoded.draft, decoded.envelope)
                }
                is PersistedMiniHomeOperationDecode.Quarantined -> {
                    if (
                        operation.state != PHASE_RECONCILIATION_REQUIRED ||
                            operation.lastErrorCode != decoded.failure.name
                    ) {
                        if (
                            markEnvelopeReconciliationRequired(
                                operation,
                                decoded.failure,
                                decoded.details,
                            ) == null
                        ) {
                            return@repeat
                        }
                        return@repeat
                    }
                    return operation.quarantinedPending(decoded)
                }
            }
        }
        return pendingSnapshot(account, loadId)
    }

    private suspend fun pendingSnapshot(
        account: AccountId,
        loadId: MiniHomeLoadIdentity? = null,
    ): MiniHomePendingSave? {
        val operation = activePendingOperation(account, loadId) ?: return null
        return when (val decoded = decodePersistedOperation(operation)) {
            is PersistedMiniHomeOperationDecode.Canonical ->
                operation.pending(decoded.draft, decoded.envelope)
            is PersistedMiniHomeOperationDecode.Quarantined -> operation.quarantinedPending(decoded)
        }
    }

    private suspend fun pendingChanged(account: AccountId): MiniHomeSaveResult.PendingChanged =
        MiniHomeSaveResult.PendingChanged(pending(account))

    private fun decodePersistedOperation(
        operation: OperationOutboxEntity
    ): PersistedMiniHomeOperationDecode {
        val envelope =
            when (
                val decoded =
                    MiniHomeDraftCodec.decodePersisted(
                        operation.draftPayload,
                        operation.payloadHash,
                    )
            ) {
                is PersistedMiniHomeEnvelopeDecode.Decoded -> decoded.envelope
                is PersistedMiniHomeEnvelopeDecode.Malformed ->
                    return PersistedMiniHomeOperationDecode.Quarantined(decoded.reason)
            }
        val recomputedPayloadHash = envelope.exactPayloadHash()
        val draft = envelope.canonicalDraft()
        if (!envelope.storedPayloadHashMatches()) {
            return PersistedMiniHomeOperationDecode.Quarantined(
                "stored payload hash does not match recomputed raw envelope hash",
                MiniHomeSaveFailure.PAYLOAD_MISMATCH,
                envelope,
                draft,
                recomputedPayloadHash,
            )
        }
        if (draft == null) {
            return PersistedMiniHomeOperationDecode.Quarantined(
                "persisted envelope cannot form a canonical mini-home draft",
                envelope = envelope,
                recomputedPayloadHash = recomputedPayloadHash,
            )
        }
        if (
            draft.owner.value != operation.accountId ||
                draft.operationId.value != operation.operationId ||
                draft.expectedRevision.value != operation.expectedRevision ||
                draft.lineageId.value != (operation.lineageId ?: operation.operationId) ||
                draft.supersedesOperationId?.value != operation.supersedesOperationId
        ) {
            return PersistedMiniHomeOperationDecode.Quarantined(
                "persisted envelope identity does not match its durable row",
                envelope = envelope,
                draft = draft,
                recomputedPayloadHash = recomputedPayloadHash,
            )
        }
        return PersistedMiniHomeOperationDecode.Canonical(
            envelope,
            draft,
            recomputedPayloadHash,
        )
    }

    private suspend fun markEnvelopeReconciliationRequired(
        operation: OperationOutboxEntity,
        failure: MiniHomeSaveFailure,
        details: String,
    ): OperationOutboxEntity? {
        if (
            operation.state == PHASE_RECONCILIATION_REQUIRED &&
                operation.lastErrorCode == failure.name &&
                operation.failureDetails == details
        ) {
            return operation
        }
        return compareAndSet(operation) {
            it.copy(
                state = PHASE_RECONCILIATION_REQUIRED,
                lastErrorCode = failure.name,
                failureDetails = details,
            )
        }
    }

    private fun OperationOutboxEntity.pending(
        restored: RestoredMiniHomeDraft,
        envelope: PersistedMiniHomeEnvelope,
    ): MiniHomePendingSave =
        MiniHomePendingSave(
            restored.operationId,
            restored.expectedRevision,
            restored.layout,
            pendingState(),
            pendingFailure(),
            failureDetails,
            OperationId(lineageId ?: operationId),
            supersedesOperationId?.let(::OperationId),
            discardHandle(),
            reconciliationDetails(envelope, envelope.exactPayloadHash()),
        )

    private fun OperationOutboxEntity.quarantinedPending(
        decoded: PersistedMiniHomeOperationDecode.Quarantined
    ): MiniHomePendingSave {
        val expected = Revision(expectedRevision.coerceAtLeast(0))
        val safeHomeId = runCatching {
            MiniHomeId(aggregateId)
        }
            .getOrElse { MiniHomeId(DEFAULT_HOME_ID) }
        val syntheticOperationId = syntheticOperationId()
        val safeLayout =
            decoded.draft?.layout
                ?: MiniHomeLayout(
                    safeHomeId,
                    DEFAULT_NAME,
                    emptyList(),
                    expected,
                    Instant.EPOCH,
                )
        return MiniHomePendingSave(
            syntheticOperationId,
            expected,
            safeLayout,
            MiniHomePendingState.RECONCILIATION_REQUIRED,
            decoded.failure,
            failureDetails ?: decoded.details,
            runCatching { OperationId(lineageId ?: operationId) }
                .getOrElse { syntheticOperationId },
            supersedesOperationId?.let { runCatching { OperationId(it) }.getOrNull() },
            discardHandle(),
            reconciliationDetails(decoded.envelope, decoded.recomputedPayloadHash),
        )
    }

    private fun OperationOutboxEntity.discardHandle() =
        MiniHomeDiscardHandle(
            AccountId(accountId),
            aggregateType,
            operationId,
            lineageId,
            rowHandleId,
            rowVersion,
        )

    private fun OperationOutboxEntity.reconciliationDetails(
        envelope: PersistedMiniHomeEnvelope?,
        recomputedPayloadHash: String?,
    ) =
        MiniHomeReconciliationDetails(
            operationId,
            lineageId,
            draftPayload,
            envelope?.operationId,
            envelope?.rawName,
            payloadHash,
            recomputedPayloadHash,
            committedOperationId,
            committedExpectedRevision,
            committedRevision,
            committedPayloadHash,
        )

    private fun OperationOutboxEntity.syntheticOperationId(): OperationId {
        runCatching { OperationId(operationId) }
            .getOrNull()
            ?.let {
                return it
            }
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "$accountId|$aggregateType|$operationId|${lineageId.orEmpty()}"
                        .toByteArray(StandardCharsets.UTF_8)
                )
        return OperationId(digest.joinToString("") { "%02x".format(it) })
    }

    private fun OperationOutboxEntity.pendingState(): MiniHomePendingState =
        when (state) {
            PHASE_MAY_HAVE_COMMITTED -> MiniHomePendingState.MAY_HAVE_COMMITTED
            PHASE_RECONCILIATION_REQUIRED,
            "CONFLICT" -> MiniHomePendingState.RECONCILIATION_REQUIRED
            else -> MiniHomePendingState.PENDING
        }

    private fun OperationOutboxEntity.pendingFailure(): MiniHomeSaveFailure? =
        lastErrorCode?.let { code ->
            MiniHomeSaveFailure.entries.firstOrNull { it.name == code }
        }

    private fun OperationOutboxEntity.receiptMatches(
        snapshot: RemoteMiniHomeSnapshot,
        decoded: PersistedMiniHomeOperationDecode.Canonical,
        exactPayloadHash: String,
    ): Boolean =
        snapshot.committedOperationId == decoded.draft.operationId &&
            snapshot.committedExpectedRevision == decoded.draft.expectedRevision &&
            MiniHomePayloadHash.constantTimeEquals(
                exactPayloadHash,
                snapshot.committedPayloadHash,
            ) &&
            snapshot.layout?.revision == decoded.draft.expectedRevision.next() &&
            snapshot.layout.sameContent(decoded.draft.layout)

    private fun RemoteMiniHomeSnapshot.receiptMatches(request: MiniHomeSaveRequest): Boolean =
        committedOperationId == request.operationId &&
            committedExpectedRevision == request.expectedRevision &&
            MiniHomePayloadHash.constantTimeEquals(
                MiniHomePayloadHash.create(request.expectedRevision, request.layout),
                committedPayloadHash,
            ) &&
            layout?.revision == request.expectedRevision.next() &&
            layout.sameContent(request.layout)

    private fun RemoteMiniHomeSnapshot.mismatchReason(
        request: MiniHomeSaveRequest
    ): MiniHomeSaveFailure =
        when {
            committedOperationId == request.operationId &&
                !MiniHomePayloadHash.constantTimeEquals(
                    MiniHomePayloadHash.create(request.expectedRevision, request.layout),
                    committedPayloadHash,
                ) -> MiniHomeSaveFailure.PAYLOAD_MISMATCH
            committedOperationId == request.operationId -> MiniHomeSaveFailure.OUTBOX_MISMATCH
            layout?.revision != request.expectedRevision -> MiniHomeSaveFailure.REVISION_CONFLICT
            else -> MiniHomeSaveFailure.OUTBOX_MISMATCH
        }

    private fun RemoteMiniHomeSnapshot.mismatchReason(
        restored: RestoredMiniHomeDraft,
        exactPayloadHash: String,
    ): MiniHomeSaveFailure =
        when {
            committedOperationId == restored.operationId &&
                !MiniHomePayloadHash.constantTimeEquals(exactPayloadHash, committedPayloadHash) ->
                MiniHomeSaveFailure.PAYLOAD_MISMATCH
            committedOperationId == restored.operationId -> MiniHomeSaveFailure.OUTBOX_MISMATCH
            layout?.revision != restored.expectedRevision -> MiniHomeSaveFailure.REVISION_CONFLICT
            else -> MiniHomeSaveFailure.OUTBOX_MISMATCH
        }

    private fun mismatchDetails(
        operation: OperationOutboxEntity,
        snapshot: RemoteMiniHomeSnapshot,
    ): String =
        "localPayloadHash=${operation.payloadHash.orEmpty()};" +
            "committedPayloadHash=${snapshot.committedPayloadHash.orEmpty()};" +
            "authoritativeRevision=${snapshot.layout?.revision?.value ?: -1}"

    private suspend fun ensurePayloadHash(
        operation: OperationOutboxEntity,
        exactPayloadHash: String,
    ): OperationOutboxEntity? {
        if (operation.payloadHash != null) return operation
        return compareAndSet(operation) { it.copy(payloadHash = exactPayloadHash) }
    }

    private fun RestoredMiniHomeDraft.request() =
        MiniHomeSaveRequest(
            owner,
            operationId,
            expectedRevision,
            layout,
            lineageId,
            supersedesOperationId,
        )

    private suspend fun recordReceipt(
        operation: OperationOutboxEntity,
        committedOperationId: OperationId,
        committedExpectedRevision: Revision,
        committedRevision: Revision,
        committedPayloadHash: String,
    ): OperationOutboxEntity? =
        compareAndSet(operation) {
            it.copy(
                committedOperationId = committedOperationId.value,
                committedExpectedRevision = committedExpectedRevision.value,
                committedRevision = committedRevision.value,
                committedPayloadHash = committedPayloadHash,
            )
        }

    private suspend fun persistAuthoritativeReceipt(
        operation: OperationOutboxEntity,
        snapshot: RemoteMiniHomeSnapshot,
    ): OperationOutboxEntity? {
        val committedOperationId = snapshot.committedOperationId ?: return operation
        val committedExpectedRevision = snapshot.committedExpectedRevision ?: return operation
        val committedRevision = snapshot.layout?.revision ?: return operation
        val committedPayloadHash = snapshot.committedPayloadHash ?: return operation
        if (
            operation.committedOperationId == committedOperationId.value &&
                operation.committedExpectedRevision == committedExpectedRevision.value &&
                operation.committedRevision == committedRevision.value &&
                operation.committedPayloadHash == committedPayloadHash
        ) {
            return operation
        }
        return recordReceipt(
            operation,
            committedOperationId,
            committedExpectedRevision,
            committedRevision,
            committedPayloadHash,
        )
    }

    private suspend fun markReconciliationRequired(
        operation: OperationOutboxEntity,
        reason: String,
        details: String,
        snapshot: RemoteMiniHomeSnapshot,
    ): OperationOutboxEntity? =
        compareAndSet(operation) {
            it.copy(
                state = PHASE_RECONCILIATION_REQUIRED,
                lastErrorCode = reason,
                failureDetails = details,
                actualRevision = snapshot.layout?.revision?.value,
                committedOperationId = snapshot.committedOperationId?.value,
                committedExpectedRevision = snapshot.committedExpectedRevision?.value,
                committedRevision = snapshot.layout?.revision?.value,
                committedPayloadHash = snapshot.committedPayloadHash,
            )
        }

    private fun blankLayout() =
        MiniHomeLayout(
            MiniHomeId(DEFAULT_HOME_ID),
            DEFAULT_NAME,
            emptyList(),
            Revision(0),
            Instant.EPOCH,
        )

    private suspend fun compareAndSet(
        operation: OperationOutboxEntity,
        transform: (OperationOutboxEntity) -> OperationOutboxEntity,
    ): OperationOutboxEntity? {
        if (remote.activeAccount()?.value != operation.accountId) return null
        return when (
            val result = database.syncDao().compareAndSetOperation(operation, transform(operation))
        ) {
            is OperationOutboxCompareAndSetResult.Updated -> result.operation
            is OperationOutboxCompareAndSetResult.Stale -> null
        }
    }

    private fun OperationOutboxEntity.matches(other: OperationOutboxEntity): Boolean =
        accountId == other.accountId &&
            aggregateType == other.aggregateType &&
            aggregateId == other.aggregateId &&
            mutationType == other.mutationType &&
            expectedRevision == other.expectedRevision &&
            draftPayload == other.draftPayload &&
            payloadHash == other.payloadHash &&
            lineageId == other.lineageId &&
            supersedesOperationId == other.supersedesOperationId

    private fun MiniHomeLayout.sameContent(other: MiniHomeLayout): Boolean =
        id == other.id &&
            name == other.name &&
            MiniHomePlacementPolicy.layer(placements) ==
                MiniHomePlacementPolicy.layer(other.placements)

    private fun CachedMiniHomePlacementEntity.placement(): MiniHomePlacement {
        require((plantId == null) != (itemId == null))
        return MiniHomePlacement(
            PlacementId(placementId),
            plantId?.let { MiniHomePlacementTarget.Plant(PersonalPlantId(it)) }
                ?: MiniHomePlacementTarget.Decoration(ItemId(requireNotNull(itemId))),
            GridPosition.parsePersisted(normalizedX, normalizedY),
            MiniHomeZIndex(zIndex),
        )
    }

    private data class RegisteredSaveOutcome(
        val operationId: OperationId,
        val result: MiniHomeSaveResult,
    )

    private suspend fun <T> withOwnerOperation(
        accountId: AccountId,
        block: suspend () -> T,
    ): T = ownerOperations.computeIfAbsent(accountId.value) { Mutex() }.withLock { block() }

    private companion object {
        const val OUTBOX_TYPE = "miniHomeLayouts"
        const val PHASE_MAY_HAVE_COMMITTED = "MAY_HAVE_COMMITTED"
        const val PHASE_RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED"
        const val DEFAULT_HOME_ID = "primary"
        const val DEFAULT_NAME = "나의 미니 식물원"
        const val MAX_PENDING_CAS_ATTEMPTS = 4
        const val MAX_PUBLICATION_REREADS = 4
    }
}

internal fun AuthoritativeInventory.miniHomeDecorationChoices(): List<MiniHomeDecorationChoice> {
    val catalogById = catalog.associate { item ->
        item.itemId to
            MiniHomeDecorationChoice(
                item.itemId,
                item.name,
                item.category,
                item.mediaIdentity,
                availableForApplication = true,
            )
    }
    return owned.map { ownership ->
        when (ownership.availability) {
            AuthoritativeInventoryAvailability.AVAILABLE ->
                catalogById[ownership.itemId]
                    ?: throw InconsistentInventoryException(
                        "available ownership is absent from catalog"
                    )
            AuthoritativeInventoryAvailability.UNAVAILABLE -> {
                val snapshot = ownership.catalogSnapshot
                MiniHomeDecorationChoice(
                    ownership.itemId,
                    snapshot?.name ?: "사용할 수 없는 아이템",
                    snapshot?.category,
                    snapshot?.mediaIdentity,
                    availableForApplication = false,
                )
            }
        }
    }
}

class FirebaseMiniHomeRemoteDataSource(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val snapshotReader: AuthoritativeMiniHomeSnapshotReader =
        AuthoritativeMiniHomeSnapshotReader(functions),
) : MiniHomeRemoteDataSource {
    override fun activeAccount(): AccountId? = auth.currentUser?.uid?.let(::AccountId)

    override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
        require(activeAccount() == accountId)
        val snapshot = snapshotReader.read(accountId)
        ensureAccount(accountId)
        val authoritative = snapshot.layout
        val authoritativeLayout =
            (authoritative as? AuthoritativeMiniHomeLayoutRead.Present)?.layout
        val authoritativeDeletion = authoritative as? AuthoritativeMiniHomeLayoutRead.Missing
        val layout = authoritativeLayout?.let { remote ->
            MiniHomeLayout(
                MiniHomeId(remote.id),
                remote.name,
                MiniHomePlacementPolicy.layer(
                    remote.placements.map { placement ->
                        MiniHomePlacement(
                            PlacementId(placement.id),
                            placement.plantId?.let {
                                MiniHomePlacementTarget.Plant(PersonalPlantId(it))
                            }
                                ?: MiniHomePlacementTarget.Decoration(
                                    ItemId(requireNotNull(placement.itemId))
                                ),
                            GridPosition.parsePersisted(
                                placement.normalizedX,
                                placement.normalizedY,
                            ),
                            MiniHomeZIndex(placement.zIndex),
                        )
                    }
                ),
                Revision(remote.revision),
                Instant.ofEpochMilli(remote.updatedAtEpochMillis),
            )
        }
        val plants =
            snapshot.plants.map {
                MiniHomePlantChoice(it.id, it.displayName, it.representativePhotoPath)
            }
        val inventory = snapshot.inventory
        ensureAccount(accountId)
        val decorations = inventory.miniHomeDecorationChoices()
        ensureAccount(accountId)
        return RemoteMiniHomeSnapshot(
            accountId,
            layout,
            plants,
            decorations,
            authoritativeLayout?.idempotencyKey?.let(::OperationId),
            authoritativeLayout?.expectedRevision?.let(::Revision),
            authoritativeLayout?.requestHash,
            cacheGeneration =
                authoritativeLayout?.generation ?: requireNotNull(authoritativeDeletion).generation,
            cacheOperationId = authoritativeLayout?.idempotencyKey,
            cachePayloadHash = authoritativeLayout?.requestHash,
            deletionTombstoneId = authoritativeDeletion?.tombstoneId,
            authoritativeAtEpochMillis =
                authoritativeLayout?.updatedAtEpochMillis
                    ?: requireNotNull(authoritativeDeletion).updatedAtEpochMillis,
            authoritativeInventory = inventory,
            snapshotToken = snapshot.token,
            snapshotGeneration = snapshot.generation,
        )
    }

    override suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult {
        if (activeAccount() != request.accountId) {
            return RemoteMiniHomeSaveResult.Failed(
                MiniHomeSaveFailure.PERMISSION_DENIED,
                "reason=PERMISSION_DENIED;field=expectedOwnerUid",
            )
        }
        return try {
            val response =
                functions
                    .getHttpsCallable("saveMiniHomeLayout")
                    .call(
                        mapOf(
                            "expectedOwnerUid" to request.accountId.value,
                            "miniHomeId" to request.layout.id.value,
                            "expectedRevision" to request.expectedRevision.value,
                            "idempotencyKey" to request.operationId.value,
                            "name" to request.layout.name,
                            "placements" to
                                request.layout.placements.map { placement ->
                                    mapOf(
                                        "placementId" to placement.id.value,
                                        "plantId" to
                                            (placement.target as? MiniHomePlacementTarget.Plant)
                                                ?.plantId
                                                ?.value,
                                        "itemId" to
                                            (placement.target
                                                    as? MiniHomePlacementTarget.Decoration)
                                                ?.itemId
                                                ?.value,
                                        "normalizedX" to placement.position.normalizedX.value,
                                        "normalizedY" to placement.position.normalizedY.value,
                                        "zIndex" to placement.zIndex.value,
                                    )
                                },
                        )
                    )
                    .await()
                    .data as? Map<*, *>
                    ?: return malformedMiniHomeResponse("callable result must be an object")
            ensureAccount(request.accountId)
            val revision = response.revision("revision")
            val actual = response.revision("actualRevision")
            when (response["kind"] as? String) {
                "applied" ->
                    revision?.let(RemoteMiniHomeSaveResult::Applied)
                        ?: malformedMiniHomeResponse("applied result revision is invalid")
                "duplicate" ->
                    revision?.let(RemoteMiniHomeSaveResult::Duplicate)
                        ?: malformedMiniHomeResponse("duplicate result revision is invalid")
                "conflict" ->
                    actual?.let(RemoteMiniHomeSaveResult::Conflict)
                        ?: malformedMiniHomeResponse("conflict result revision is invalid")
                else -> malformedMiniHomeResponse("callable result kind is invalid")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            val details = error.details as? Map<*, *>
            RemoteMiniHomeSaveResult.Failed(
                mapMiniHomeCallableFailure(error.code, details),
                miniHomeCallableFailureDetails(details),
                (details?.get("committedOperationId") as? String)?.let(::OperationId),
                (details?.get("committedExpectedRevision") as? Number)?.toLong()?.let(::Revision),
                (details?.get("committedRevision") as? Number)?.toLong()?.let(::Revision),
                details?.get("committedPayloadHash") as? String,
            )
        } catch (_: IOException) {
            RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
        } catch (_: Exception) {
            RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.MALFORMED_RESPONSE)
        }
    }

    private fun ensureAccount(accountId: AccountId) {
        if (activeAccount() != accountId) throw SecurityException("Active account changed")
    }
}

internal fun recoverLegacyMiniHomeName(legacyName: String): String? {
    if (legacyName.isEmpty()) return null
    val canonicalName =
        runCatching { Normalizer.normalize(legacyName, Normalizer.Form.NFC) }.getOrNull()
            ?: return null
    return canonicalName.takeIf { MiniHomeRequestContract.validateName(it) == null }
}

internal fun mapMiniHomeCallableFailure(
    code: FirebaseFunctionsException.Code,
    details: Any?,
): MiniHomeSaveFailure {
    val reason = (details as? Map<*, *>)?.get("reason") as? String
    return when (reason) {
        "OUTBOX_MISMATCH" -> MiniHomeSaveFailure.OUTBOX_MISMATCH
        "PAYLOAD_MISMATCH" -> MiniHomeSaveFailure.PAYLOAD_MISMATCH
        "UNAVAILABLE_ENTITY" -> MiniHomeSaveFailure.UNAVAILABLE_ENTITY
        "REVISION_CONFLICT" -> MiniHomeSaveFailure.REVISION_CONFLICT
        "INVALID_REQUEST" -> MiniHomeSaveFailure.INVALID_REQUEST
        "PERMISSION_DENIED" -> MiniHomeSaveFailure.PERMISSION_DENIED
        "MALFORMED_RESPONSE" -> MiniHomeSaveFailure.MALFORMED_RESPONSE
        else ->
            when (code) {
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.ALREADY_EXISTS,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.OUT_OF_RANGE,
                FirebaseFunctionsException.Code.UNIMPLEMENTED -> MiniHomeSaveFailure.INVALID_REQUEST
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    MiniHomeSaveFailure.PERMISSION_DENIED
                FirebaseFunctionsException.Code.ABORTED -> MiniHomeSaveFailure.REVISION_CONFLICT
                FirebaseFunctionsException.Code.DATA_LOSS,
                FirebaseFunctionsException.Code.UNKNOWN,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.OK -> MiniHomeSaveFailure.MALFORMED_RESPONSE
                FirebaseFunctionsException.Code.CANCELLED,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                FirebaseFunctionsException.Code.UNAVAILABLE -> MiniHomeSaveFailure.NETWORK
            }
    }
}

internal fun miniHomeCallableFailureDetails(details: Any?): String? =
    (details as? Map<*, *>)
        ?.entries
        ?.mapNotNull { (key, value) ->
            (key as? String)?.let { "$it=${value?.toString().orEmpty()}" }
        }
        ?.sorted()
        ?.joinToString(";")
        ?.takeIf(String::isNotEmpty)

private fun Map<*, *>.revision(field: String): Revision? {
    val number = this[field] as? Number ?: return null
    val value = number.toLong()
    if (
        number.toDouble() != value.toDouble() ||
            value !in 0..MiniHomeRequestContract.MAX_SAFE_REVISION
    ) {
        return null
    }
    return Revision(value)
}

private fun malformedMiniHomeResponse(details: String) =
    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.MALFORMED_RESPONSE, details)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}
