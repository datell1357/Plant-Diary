package com.planterior.helper.feature.shop

import android.os.SystemClock
import androidx.room.withTransaction
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.data.AuthoritativeCatalogItem
import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.AuthoritativeInventoryAvailability
import com.planterior.helper.core.data.AuthoritativeInventoryCondition
import com.planterior.helper.core.data.AuthoritativeInventoryReader
import com.planterior.helper.core.data.AuthoritativeOwnedItem
import com.planterior.helper.core.data.verifiedAuthoritativeInventory
import com.planterior.helper.core.data.verifiedAuthoritativeInventoryOrNull
import com.planterior.helper.core.database.AuthoritativeInventoryCacheWrite
import com.planterior.helper.core.database.CachedOwnedItemEntity
import com.planterior.helper.core.database.CachedShopItemEntity
import com.planterior.helper.core.database.InventoryAcquisitionOperationEntity
import com.planterior.helper.core.database.InventoryCacheApplyResult
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine

data class RemoteInventoryAcquireRequest(
    val accountId: AccountId,
    val itemId: ItemId,
    val expectedCatalogRevision: Revision,
    val operationId: OperationId,
    val requestHash: String,
)

sealed interface RemoteInventoryAcquireResult {
    data class Acquired(
        val accountId: AccountId,
        val itemId: ItemId,
        val catalogRevision: Revision,
        val ownershipRevision: Revision,
        val acquiredAt: Instant,
        val mediaIdentity: CatalogMediaIdentity,
    ) : RemoteInventoryAcquireResult

    data class AlreadyOwned(
        val accountId: AccountId,
        val itemId: ItemId,
        val catalogRevision: Revision,
        val ownershipRevision: Revision,
        val acquiredAt: Instant,
        val mediaIdentity: CatalogMediaIdentity,
    ) : RemoteInventoryAcquireResult

    data class ConditionNotMet(
        val accountId: AccountId,
        val itemId: ItemId,
        val catalogRevision: Revision,
        val condition: AcquisitionCondition,
    ) : RemoteInventoryAcquireResult
}

internal class InventoryRemoteException(val failure: InventoryFailure) : IOException(failure.name)

interface InventoryRemoteDataSource {
    fun activeAccount(): AccountId?

    suspend fun load(accountId: AccountId): InventorySnapshot

    suspend fun acquire(request: RemoteInventoryAcquireRequest): RemoteInventoryAcquireResult
}

class FirebaseInventoryRepository(
    private val database: PlanteriorDatabase,
    private val remote: InventoryRemoteDataSource,
    loadScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val now: () -> Instant = Instant::now,
) : InventoryRepository, AutoCloseable {
    constructor(
        database: PlanteriorDatabase,
        remote: InventoryRemoteDataSource,
        now: () -> Instant,
    ) : this(database, remote, CoroutineScope(Dispatchers.IO), SystemClock::elapsedRealtime, now)

    private val loadScope =
        CoroutineScope(loadScope.coroutineContext + SupervisorJob(loadScope.coroutineContext[Job]))
    private val loadFlights = mutableMapOf<AccountId, Deferred<InventoryLoadResult>>()
    private val loadFlightsLock = Any()
    private val freshUntil = mutableMapOf<AccountId, Long>()
    private var observedAccount: AccountId? = null
    private var accountEpoch = 0L

    /** Called by the process-owned auth runtime for every Firebase account transition. */
    fun onAccountChanged(accountId: AccountId?) {
        synchronized(loadFlightsLock) { observeAccount(accountId) }
    }

    override fun close() {
        loadScope.cancel()
    }

    override suspend fun load(): InventoryLoadResult = load(false)

    override suspend fun load(forceRefresh: Boolean): InventoryLoadResult {
        val accountId = remote.activeAccount() ?: return InventoryLoadResult.Forbidden
        val epoch = observeAccount(accountId)
        if (!forceRefresh) {
            val cached = cached(accountId)
            if (cached != null) {
                val fresh =
                    synchronized(loadFlightsLock) {
                        cached.loadedAt.toEpochMilli() <= now().toEpochMilli() &&
                            freshUntil[accountId]?.let { elapsedRealtime() < it } == true
                    }
                val candidates = runCatching { receiptCandidates(accountId) }
                if (!isCurrentLoadOwner(accountId, epoch)) return InventoryLoadResult.Forbidden
                return cached.loadResult(
                    stale = !fresh,
                    receiptCandidates = candidates.getOrDefault(emptyList()),
                    receiptCandidatesAuthoritative = candidates.isSuccess,
                    refreshRequired = !fresh,
                )
            }
        }
        val flight =
            synchronized(loadFlightsLock) {
                if (!isCurrentLoadOwner(accountId, epoch)) return InventoryLoadResult.Forbidden
                loadFlights[accountId]?.takeIf { it.isActive }
                    ?: loadScope
                        .async(start = CoroutineStart.LAZY) { loadRemote(accountId, epoch) }
                        .also { created ->
                            loadFlights[accountId] = created
                            created.invokeOnCompletion {
                                synchronized(loadFlightsLock) {
                                    loadFlights.remove(accountId, created)
                                }
                            }
                            created.start()
                        }
            }
        return flight.await()
    }

    private fun observeAccount(accountId: AccountId?): Long =
        synchronized(loadFlightsLock) {
            if (observedAccount != accountId) {
                val previous = observedAccount
                observedAccount = accountId
                accountEpoch += 1
                freshUntil.remove(previous)
                freshUntil.remove(accountId)
                loadFlights.remove(previous)?.cancel()
            }
            accountEpoch
        }

    private suspend fun loadRemote(accountId: AccountId, epoch: Long): InventoryLoadResult {
        var receiptCandidates = emptyList<InventoryReceiptId>()
        return try {
            ensureLoadOwner(accountId, epoch)
            reconcilePending(accountId)
            ensureLoadOwner(accountId, epoch)
            val snapshot = remote.load(accountId)
            require(snapshot.accountId == accountId)
            ensureLoadOwner(accountId, epoch)
            val current =
                database.withTransaction {
                    if (!isCurrentLoadOwner(accountId, epoch)) return@withTransaction null
                    val applied =
                        database
                            .cacheDao()
                            .applyAuthoritativeInventory(snapshot.authoritativeWrite())
                    if (applied is InventoryCacheApplyResult.Conflict) {
                        throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
                    }
                    val current =
                        requireNotNull(
                                applied.current.verifiedAuthoritativeInventoryOrNull(accountId)
                            ) {
                                "Authoritative inventory cache result is unverified"
                            }
                            .inventorySnapshot()
                    database
                        .syncDao()
                        .upsertLastSync(
                            LastSyncEntity(
                                accountId.value,
                                INVENTORY_DOMAIN,
                                current.loadedAt.toEpochMilli(),
                                if (current.partial) "PARTIAL" else "SUCCESS",
                                null,
                            )
                        )
                    ensureLoadOwner(accountId, epoch)
                    current
                } ?: return InventoryLoadResult.Forbidden
            ensureLoadOwner(accountId, epoch)
            receiptCandidates = receiptCandidates(accountId)
            compactAcknowledgedReceipts(accountId)
            synchronized(loadFlightsLock) {
                ensureLoadOwner(accountId, epoch)
                val elapsed = elapsedRealtime()
                freshUntil[accountId] =
                    if (elapsed > Long.MAX_VALUE - FRESHNESS_MILLIS) Long.MAX_VALUE
                    else elapsed + FRESHNESS_MILLIS
            }
            current.loadResult(stale = false, receiptCandidates, refreshRequired = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!isCurrentLoadOwner(accountId, epoch)) return InventoryLoadResult.Forbidden
            val snapshot = cached(accountId) ?: return InventoryLoadResult.Failed
            val candidateRead = runCatching { receiptCandidates(accountId) }
            if (!isCurrentLoadOwner(accountId, epoch)) return InventoryLoadResult.Forbidden
            snapshot.loadResult(
                stale = true,
                receiptCandidates = candidateRead.getOrDefault(emptyList()),
                receiptCandidatesAuthoritative = candidateRead.isSuccess,
                refreshRequired = false,
            )
        }
    }

    override suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult {
        if (remote.activeAccount() != request.accountId) return InventoryAcquireResult.Forbidden
        val remoteRequest = request.remote()
        val operation =
            InventoryAcquisitionOperationEntity(
                request.accountId.value,
                request.operationId.value,
                request.itemId.value,
                request.expectedCatalogRevision.value,
                remoteRequest.requestHash,
                now().toEpochMilli(),
            )
        return try {
            val inserted = database.inventoryDao().insertOperation(operation)
            if (inserted == -1L) {
                when (val persisted = persistedOperation(remoteRequest, operation)) {
                    PersistedAcquisition.Pending -> Unit
                    is PersistedAcquisition.Terminal -> {
                        invalidateAfterAcquisition(request.accountId, persisted.result)
                        return persisted.result
                    }
                    is PersistedAcquisition.Failed -> return persisted.result
                    PersistedAcquisition.Mismatch ->
                        return InventoryAcquireResult.Failure(
                            InventoryFailure.IDEMPOTENCY_MISMATCH,
                            request.operationId,
                        )
                    PersistedAcquisition.Missing,
                    PersistedAcquisition.Invalid ->
                        return InventoryAcquireResult.Failure(
                            InventoryFailure.DATABASE,
                            request.operationId,
                        )
                }
            }
            complete(remoteRequest).also { result ->
                invalidateAfterAcquisition(request.accountId, result)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            InventoryAcquireResult.Failure(InventoryFailure.DATABASE, request.operationId)
        }
    }

    private fun invalidateAfterAcquisition(accountId: AccountId, result: InventoryAcquireResult) {
        if (
            result !is InventoryAcquireResult.Success &&
                result !is InventoryAcquireResult.AlreadyOwned
        )
            return
        synchronized(loadFlightsLock) {
            freshUntil.remove(accountId)
            if (observedAccount == accountId) accountEpoch += 1
            loadFlights.remove(accountId)?.cancel()
        }
    }

    private suspend fun reconcilePending(accountId: AccountId) {
        database.inventoryDao().pendingOperations(accountId.value).forEach { operation ->
            if (remote.activeAccount() != accountId) return
            val request =
                RemoteInventoryAcquireRequest(
                    accountId,
                    ItemId(operation.itemId),
                    Revision(operation.expectedCatalogRevision),
                    OperationId(operation.operationId),
                    operation.requestHash,
                )
            complete(request)
        }
    }

    private suspend fun receiptCandidates(accountId: AccountId): List<InventoryReceiptId> {
        ensureOwner(accountId)
        return database.inventoryDao().receiptsAwaitingDelivery(accountId.value).map {
            requireNotNull(it.terminalReceipt()).receiptId
        }
    }

    private suspend fun compactAcknowledgedReceipts(accountId: AccountId) {
        database
            .inventoryDao()
            .deleteAcknowledgedCompletedOperations(
                accountId.value,
                now().minus(ACKNOWLEDGED_RECEIPT_RETENTION).toEpochMilli(),
            )
    }

    override suspend fun claimForPresentation(
        receiptId: InventoryReceiptId,
        expected: InventoryReceiptPresentationExpectation?,
        claimant: InventoryReceiptClaimant,
    ): InventoryReceiptClaimResult {
        val accountId = remote.activeAccount() ?: return InventoryReceiptClaimResult.Forbidden
        val operationPrefix = "${accountId.value}/"
        if (!receiptId.value.startsWith(operationPrefix)) {
            return InventoryReceiptClaimResult.Forbidden
        }
        val operationId = receiptId.value.removePrefix(operationPrefix)
        if (
            operationId.isEmpty() || receiptId != InventoryReceiptId("$operationPrefix$operationId")
        ) {
            return InventoryReceiptClaimResult.Mismatch
        }
        return try {
            database.withTransaction {
                val current =
                    database.inventoryDao().operation(accountId.value, operationId)
                        ?: return@withTransaction InventoryReceiptClaimResult.Missing
                val persisted =
                    current.terminalReceipt()
                        ?: return@withTransaction InventoryReceiptClaimResult.Mismatch
                if (persisted.receiptId != receiptId) {
                    return@withTransaction InventoryReceiptClaimResult.Mismatch
                }
                if (expected != null && !persisted.sameReceipt(expected.receipt)) {
                    return@withTransaction InventoryReceiptClaimResult.Mismatch
                }
                if (current.feedbackDeliveryState == "ACKNOWLEDGED") {
                    return@withTransaction InventoryReceiptClaimResult.Missing
                }
                if (
                    expected?.rowVersion != null &&
                        current.feedbackRowVersion != expected.rowVersion
                ) {
                    return@withTransaction InventoryReceiptClaimResult.Stale
                }
                if (
                    current.feedbackDeliveryState in setOf("CLAIMED", "PRESENTED", "ACK_PENDING") &&
                        current.matches(claimant)
                ) {
                    return@withTransaction InventoryReceiptClaimResult.Claimed(
                        current.claim(persisted, claimant)
                    )
                }
                val claimedAt = now()
                val leaseExpiresAt = claimedAt.plus(RECEIPT_CLAIM_LEASE).toEpochMilli()
                val result = requireNotNull(current.result)
                val updated =
                    if (current.feedbackDeliveryState in setOf("PRESENTED", "ACK_PENDING")) {
                        database
                            .inventoryDao()
                            .rebindPresentedOrPendingReceipt(
                                accountId.value,
                                operationId,
                                current.itemId,
                                result,
                                current.feedbackRowVersion,
                                claimant.presentationToken,
                                claimant.controllerEpoch,
                                claimant.generation,
                                claimedAt.toEpochMilli(),
                                leaseExpiresAt,
                            )
                    } else {
                        database
                            .inventoryDao()
                            .claimCompletedReceipt(
                                accountId.value,
                                operationId,
                                current.itemId,
                                result,
                                current.feedbackRowVersion,
                                claimant.presentationToken,
                                claimant.controllerEpoch,
                                claimant.generation,
                                claimedAt.toEpochMilli(),
                                leaseExpiresAt,
                            )
                    }
                if (updated == 1) {
                    InventoryReceiptClaimResult.Claimed(
                        InventoryReceiptClaim(
                            persisted,
                            claimant,
                            current.feedbackRowVersion + 1,
                            leaseExpiresAt,
                            when (current.feedbackDeliveryState) {
                                "ACK_PENDING" -> InventoryReceiptDeliveryPhase.ACK_PENDING
                                "PRESENTED" -> InventoryReceiptDeliveryPhase.PRESENTED
                                else -> InventoryReceiptDeliveryPhase.CLAIMED
                            },
                        )
                    )
                } else {
                    val latest = database.inventoryDao().operation(accountId.value, operationId)
                    when {
                        latest == null || latest.feedbackDeliveryState == "ACKNOWLEDGED" ->
                            InventoryReceiptClaimResult.Missing
                        expected != null &&
                            latest.terminalReceipt()?.sameReceipt(expected.receipt) != true ->
                            InventoryReceiptClaimResult.Mismatch
                        expected?.rowVersion != null &&
                            latest.feedbackRowVersion != expected.rowVersion ->
                            InventoryReceiptClaimResult.Stale
                        latest.feedbackDeliveryState in
                            setOf("CLAIMED", "PRESENTED", "ACK_PENDING") &&
                            latest.matches(claimant) ->
                            InventoryReceiptClaimResult.Claimed(
                                latest.claim(requireNotNull(latest.terminalReceipt()), claimant)
                            )
                        latest.feedbackDeliveryState in
                            setOf("CLAIMED", "PRESENTED", "ACK_PENDING") &&
                            latest.feedbackClaimLeaseExpiresAtEpochMillis != null ->
                            InventoryReceiptClaimResult.Unavailable(
                                requireNotNull(latest.feedbackClaimLeaseExpiresAtEpochMillis)
                            )
                        else -> InventoryReceiptClaimResult.DatabaseFailure
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            InventoryReceiptClaimResult.DatabaseFailure
        }
    }

    override suspend fun markReceiptPresented(
        claim: InventoryReceiptClaim
    ): InventoryReceiptPresentationResult {
        val receipt = claim.receipt
        if (remote.activeAccount() != receipt.owner) {
            return InventoryReceiptPresentationResult.Forbidden
        }
        return try {
            database.withTransaction {
                val current =
                    database
                        .inventoryDao()
                        .operation(receipt.owner.value, receipt.operationId.value)
                        ?: return@withTransaction InventoryReceiptPresentationResult.Mismatch
                val persisted =
                    current.terminalReceipt()
                        ?: return@withTransaction InventoryReceiptPresentationResult.Mismatch
                if (
                    !persisted.sameReceipt(receipt) ||
                        !current.matches(claim.claimant) ||
                        current.feedbackRowVersion != claim.rowVersion
                ) {
                    return@withTransaction InventoryReceiptPresentationResult.Mismatch
                }
                if (
                    claim.deliveryPhase == InventoryReceiptDeliveryPhase.PRESENTED &&
                        current.feedbackDeliveryState == "PRESENTED"
                ) {
                    return@withTransaction InventoryReceiptPresentationResult.Presented(claim)
                }
                if (
                    claim.deliveryPhase != InventoryReceiptDeliveryPhase.CLAIMED ||
                        current.feedbackDeliveryState != "CLAIMED"
                ) {
                    return@withTransaction InventoryReceiptPresentationResult.Mismatch
                }
                val claimant = claim.claimant
                val updated =
                    database
                        .inventoryDao()
                        .markClaimedReceiptPresented(
                            receipt.owner.value,
                            receipt.operationId.value,
                            receipt.itemId.value,
                            requireNotNull(current.result),
                            claimant.presentationToken,
                            claimant.controllerEpoch,
                            claimant.generation,
                            claim.rowVersion,
                        )
                if (updated == 1) {
                    InventoryReceiptPresentationResult.Presented(
                        claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.PRESENTED)
                    )
                } else {
                    InventoryReceiptPresentationResult.Mismatch
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            InventoryReceiptPresentationResult.DatabaseFailure
        }
    }

    override suspend fun markReceiptConsumed(
        claim: InventoryReceiptClaim
    ): InventoryReceiptConsumptionResult {
        val receipt = claim.receipt
        if (remote.activeAccount() != receipt.owner) {
            return InventoryReceiptConsumptionResult.Forbidden
        }
        if (
            claim.deliveryPhase != InventoryReceiptDeliveryPhase.PRESENTED &&
                claim.deliveryPhase != InventoryReceiptDeliveryPhase.ACK_PENDING
        ) {
            return InventoryReceiptConsumptionResult.Mismatch
        }
        return try {
            database.withTransaction {
                val current =
                    database
                        .inventoryDao()
                        .operation(receipt.owner.value, receipt.operationId.value)
                        ?: return@withTransaction InventoryReceiptConsumptionResult.Missing
                val persisted =
                    current.terminalReceipt()
                        ?: return@withTransaction InventoryReceiptConsumptionResult.Mismatch
                if (!persisted.sameReceipt(receipt) || !current.matches(claim.claimant)) {
                    return@withTransaction InventoryReceiptConsumptionResult.Mismatch
                }
                when {
                    current.feedbackDeliveryState == "ACK_PENDING" &&
                        current.feedbackRowVersion == claim.rowVersion ->
                        InventoryReceiptConsumptionResult.PendingAcknowledgement(
                            claim.copy(deliveryPhase = InventoryReceiptDeliveryPhase.ACK_PENDING)
                        )
                    current.feedbackDeliveryState == "PRESENTED" &&
                        current.feedbackRowVersion == claim.rowVersion -> {
                        val claimant = claim.claimant
                        val updated =
                            database
                                .inventoryDao()
                                .markPresentedReceiptConsumed(
                                    receipt.owner.value,
                                    receipt.operationId.value,
                                    receipt.itemId.value,
                                    requireNotNull(current.result),
                                    claimant.presentationToken,
                                    claimant.controllerEpoch,
                                    claimant.generation,
                                    claim.rowVersion,
                                )
                        if (updated == 1) {
                            InventoryReceiptConsumptionResult.PendingAcknowledgement(
                                claim.copy(
                                    deliveryPhase = InventoryReceiptDeliveryPhase.ACK_PENDING
                                )
                            )
                        } else {
                            InventoryReceiptConsumptionResult.Stale
                        }
                    }
                    current.feedbackDeliveryState == "ACKNOWLEDGED" ->
                        InventoryReceiptConsumptionResult.Missing
                    current.feedbackRowVersion != claim.rowVersion ->
                        InventoryReceiptConsumptionResult.Stale
                    else -> InventoryReceiptConsumptionResult.Mismatch
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            InventoryReceiptConsumptionResult.DatabaseFailure
        }
    }

    override suspend fun acknowledgeReceipt(
        claim: InventoryReceiptClaim
    ): InventoryReceiptAcknowledgement {
        val receipt = claim.receipt
        if (remote.activeAccount() != receipt.owner) {
            return InventoryReceiptAcknowledgement.FORBIDDEN
        }
        if (claim.deliveryPhase != InventoryReceiptDeliveryPhase.ACK_PENDING) {
            return InventoryReceiptAcknowledgement.MISMATCH
        }
        return try {
            database.withTransaction {
                val current =
                    database
                        .inventoryDao()
                        .operation(receipt.owner.value, receipt.operationId.value)
                        ?: return@withTransaction InventoryReceiptAcknowledgement.MISSING
                val persisted =
                    current.terminalReceipt()
                        ?: return@withTransaction InventoryReceiptAcknowledgement.MISMATCH
                if (!persisted.sameReceipt(receipt) || !current.matches(claim.claimant)) {
                    return@withTransaction InventoryReceiptAcknowledgement.MISMATCH
                }
                when {
                    current.feedbackDeliveryState == "ACKNOWLEDGED" &&
                        current.feedbackRowVersion == claim.rowVersion + 1 ->
                        InventoryReceiptAcknowledgement.ALREADY_ACKNOWLEDGED
                    current.feedbackDeliveryState == "ACK_PENDING" &&
                        current.feedbackRowVersion == claim.rowVersion -> {
                        val claimant = claim.claimant
                        val updated =
                            database
                                .inventoryDao()
                                .acknowledgePendingReceipt(
                                    receipt.owner.value,
                                    receipt.operationId.value,
                                    receipt.itemId.value,
                                    requireNotNull(current.result),
                                    claimant.presentationToken,
                                    claimant.controllerEpoch,
                                    claimant.generation,
                                    claim.rowVersion,
                                    now().toEpochMilli(),
                                )
                        if (updated == 1) {
                            InventoryReceiptAcknowledgement.ACKNOWLEDGED
                        } else {
                            InventoryReceiptAcknowledgement.MISMATCH
                        }
                    }
                    else -> InventoryReceiptAcknowledgement.MISMATCH
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            InventoryReceiptAcknowledgement.DATABASE_FAILURE
        }
    }

    private suspend fun complete(request: RemoteInventoryAcquireRequest): InventoryAcquireResult {
        if (remote.activeAccount() != request.accountId) return InventoryAcquireResult.Forbidden
        when (val persisted = persistedOperation(request)) {
            PersistedAcquisition.Pending -> Unit
            is PersistedAcquisition.Terminal -> return persisted.result
            is PersistedAcquisition.Failed -> return persisted.result
            PersistedAcquisition.Mismatch -> return request.idempotencyMismatch()
            PersistedAcquisition.Missing,
            PersistedAcquisition.Invalid -> return request.databaseFailure()
        }
        val result =
            try {
                remote.acquire(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: InventoryRemoteException) {
                return resolveRemoteFailure(request, error.failure)
            } catch (_: IOException) {
                return resolveRemoteFailure(request, InventoryFailure.NETWORK)
            } catch (_: Exception) {
                return resolveRemoteFailure(request, InventoryFailure.MALFORMED_RESPONSE)
            }
        if (remote.activeAccount() != request.accountId) return InventoryAcquireResult.Forbidden
        return try {
            commitTerminal(request, result)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            request.databaseFailure()
        }
    }

    private suspend fun commitTerminal(
        request: RemoteInventoryAcquireRequest,
        remoteResult: RemoteInventoryAcquireResult,
    ): InventoryAcquireResult = database.withTransaction {
        val expected = request.operationEntity()
        when (val persisted = persistedOperation(request, expected)) {
            is PersistedAcquisition.Terminal -> return@withTransaction persisted.result
            is PersistedAcquisition.Failed -> return@withTransaction persisted.result
            PersistedAcquisition.Mismatch -> return@withTransaction request.idempotencyMismatch()
            PersistedAcquisition.Missing,
            PersistedAcquisition.Invalid -> return@withTransaction request.databaseFailure()
            PersistedAcquisition.Pending -> Unit
        }
        val terminal = remoteResult.terminal(request)
        val updated =
            database
                .inventoryDao()
                .completeOperation(
                    request.accountId.value,
                    request.operationId.value,
                    request.itemId.value,
                    request.expectedCatalogRevision.value,
                    request.requestHash,
                    terminal.encode(),
                    if (
                        terminal is InventoryAcquireResult.Success ||
                            terminal is InventoryAcquireResult.AlreadyOwned
                    ) {
                        "UNDELIVERED"
                    } else {
                        "NONE"
                    },
                )
        if (updated != 1) {
            return@withTransaction when (val current = persistedOperation(request, expected)) {
                is PersistedAcquisition.Terminal -> current.result
                is PersistedAcquisition.Failed -> current.result
                PersistedAcquisition.Mismatch -> request.idempotencyMismatch()
                PersistedAcquisition.Pending,
                PersistedAcquisition.Missing,
                PersistedAcquisition.Invalid -> request.databaseFailure()
            }
        }
        terminal
    }

    private suspend fun resolveRemoteFailure(
        request: RemoteInventoryAcquireRequest,
        failure: InventoryFailure,
    ): InventoryAcquireResult =
        try {
            if (remote.activeAccount() != request.accountId) {
                return InventoryAcquireResult.Forbidden
            }
            database.withTransaction {
                val expected = request.operationEntity()
                when (val persisted = persistedOperation(request, expected)) {
                    is PersistedAcquisition.Terminal -> return@withTransaction persisted.result
                    is PersistedAcquisition.Failed -> return@withTransaction persisted.result
                    PersistedAcquisition.Mismatch ->
                        return@withTransaction request.idempotencyMismatch()
                    PersistedAcquisition.Missing,
                    PersistedAcquisition.Invalid -> return@withTransaction request.databaseFailure()
                    PersistedAcquisition.Pending -> Unit
                }
                if (failure != InventoryFailure.NETWORK) {
                    val updated =
                        database
                            .inventoryDao()
                            .failOperation(
                                request.accountId.value,
                                request.operationId.value,
                                request.itemId.value,
                                request.expectedCatalogRevision.value,
                                request.requestHash,
                                failure.name,
                            )
                    if (updated != 1) {
                        return@withTransaction when (
                            val current = persistedOperation(request, expected)
                        ) {
                            is PersistedAcquisition.Terminal -> current.result
                            is PersistedAcquisition.Failed -> current.result
                            PersistedAcquisition.Mismatch -> request.idempotencyMismatch()
                            PersistedAcquisition.Pending,
                            PersistedAcquisition.Missing,
                            PersistedAcquisition.Invalid -> request.databaseFailure()
                        }
                    }
                }
                InventoryAcquireResult.Failure(failure, request.operationId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            request.databaseFailure()
        }

    private suspend fun persistedOperation(
        request: RemoteInventoryAcquireRequest,
        expected: InventoryAcquisitionOperationEntity = request.operationEntity(),
    ): PersistedAcquisition {
        val current =
            database.inventoryDao().operation(request.accountId.value, request.operationId.value)
                ?: return PersistedAcquisition.Missing
        if (!current.sameCommand(expected)) return PersistedAcquisition.Mismatch
        return when (current.state) {
            "PENDING" -> PersistedAcquisition.Pending
            "COMPLETED" ->
                current.result?.decodeTerminal(request)?.let(PersistedAcquisition::Terminal)
                    ?: PersistedAcquisition.Invalid
            "FAILED" ->
                PersistedAcquisition.Failed(
                    InventoryAcquireResult.Failure(
                        current.lastErrorCode?.let(::failureOrMalformed)
                            ?: InventoryFailure.MALFORMED_RESPONSE,
                        request.operationId,
                    )
                )
            else -> PersistedAcquisition.Invalid
        }
    }

    private suspend fun cached(accountId: AccountId): InventorySnapshot? =
        database.cacheDao().verifiedAuthoritativeInventory(accountId)?.inventorySnapshot()

    private fun ensureOwner(accountId: AccountId) {
        if (remote.activeAccount() != accountId) throw SecurityException("Inventory owner changed")
    }

    private fun ensureLoadOwner(accountId: AccountId, epoch: Long) {
        ensureOwner(accountId)
        synchronized(loadFlightsLock) {
            check(observedAccount == accountId && accountEpoch == epoch)
        }
    }

    private fun isCurrentLoadOwner(accountId: AccountId, epoch: Long): Boolean =
        remote.activeAccount() == accountId &&
            synchronized(loadFlightsLock) {
                observedAccount == accountId && accountEpoch == epoch
            }

    private companion object {
        const val INVENTORY_DOMAIN = "INVENTORY"
        val ACKNOWLEDGED_RECEIPT_RETENTION = java.time.Duration.ofDays(7)
        val RECEIPT_CLAIM_LEASE = java.time.Duration.ofMinutes(5)
        const val FRESHNESS_MILLIS = 30_000L
    }
}

private sealed interface PersistedAcquisition {
    data object Pending : PersistedAcquisition

    data class Terminal(val result: InventoryAcquireResult) : PersistedAcquisition

    data class Failed(val result: InventoryAcquireResult.Failure) : PersistedAcquisition

    data object Mismatch : PersistedAcquisition

    data object Missing : PersistedAcquisition

    data object Invalid : PersistedAcquisition
}

class FirebaseInventoryRemoteDataSource(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val inventoryReader: AuthoritativeInventoryReader =
        AuthoritativeInventoryReader(functions),
) : InventoryRemoteDataSource {
    override fun activeAccount(): AccountId? = auth.currentUser?.uid?.let(::AccountId)

    override suspend fun load(accountId: AccountId): InventorySnapshot {
        require(activeAccount() == accountId)
        val response = inventoryReader.read(accountId)
        require(activeAccount() == accountId)
        return InventorySnapshot(
            response.accountId,
            response.catalog.map(AuthoritativeCatalogItem::inventoryItem),
            response.owned.map(AuthoritativeOwnedItem::ownedInventoryItem),
            response.registeredPlantCount,
            Instant.ofEpochMilli(response.loadedAtEpochMillis),
            response.partial,
            response.generation,
            response.snapshotHash,
        )
    }

    override suspend fun acquire(
        request: RemoteInventoryAcquireRequest
    ): RemoteInventoryAcquireResult {
        require(activeAccount() == request.accountId)
        val response =
            callable(
                "acquireInventoryItem",
                mapOf(
                    "expectedOwnerUid" to request.accountId.value,
                    "itemId" to request.itemId.value,
                    "expectedCatalogRevision" to request.expectedCatalogRevision.value,
                    "operationId" to request.operationId.value,
                ),
            )
        val kind = response.requiredString("kind")
        response.requireExactFields(
            when (kind) {
                "acquired",
                "already-owned" -> ACQUISITION_RECEIPT_FIELDS
                "condition-not-met" -> CONDITION_NOT_MET_FIELDS
                else -> throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
            }
        )
        val owner = AccountId(response.requiredString("ownerUid"))
        val itemId = ItemId(response.requiredString("itemId"))
        val catalogRevision = Revision(response.requiredLong("catalogRevision"))
        if (owner != request.accountId || itemId != request.itemId) {
            throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
        }
        return when (kind) {
            "acquired" ->
                RemoteInventoryAcquireResult.Acquired(
                    owner,
                    itemId,
                    catalogRevision,
                    Revision(response.requiredLong("ownershipRevision")),
                    Instant.ofEpochMilli(response.requiredLong("acquiredAtEpochMillis")),
                    response.requiredMediaIdentity(itemId),
                )
            "already-owned" ->
                RemoteInventoryAcquireResult.AlreadyOwned(
                    owner,
                    itemId,
                    catalogRevision,
                    Revision(response.requiredLong("ownershipRevision")),
                    Instant.ofEpochMilli(response.requiredLong("acquiredAtEpochMillis")),
                    response.requiredMediaIdentity(itemId),
                )
            "condition-not-met" ->
                RemoteInventoryAcquireResult.ConditionNotMet(
                    owner,
                    itemId,
                    catalogRevision,
                    AcquisitionCondition.parse(response.requiredString("condition")),
                )
            else -> throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
        }
    }

    private suspend fun callable(name: String, payload: Map<String, Any>): Map<*, *> =
        try {
            functions.getHttpsCallable(name).call(payload).await().data as? Map<*, *>
                ?: throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
        } catch (error: FirebaseFunctionsException) {
            val reason = (error.details as? Map<*, *>)?.get("reason") as? String
            throw InventoryRemoteException(
                when (reason) {
                    "CATALOG_CHANGED" -> InventoryFailure.CATALOG_CHANGED
                    "ITEM_UNAVAILABLE" -> InventoryFailure.ITEM_UNAVAILABLE
                    "IDEMPOTENCY_MISMATCH" -> InventoryFailure.IDEMPOTENCY_MISMATCH
                    "MALFORMED_RESPONSE" -> InventoryFailure.MALFORMED_RESPONSE
                    else ->
                        if (
                            error.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                                error.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
                        ) {
                            InventoryFailure.NETWORK
                        } else {
                            InventoryFailure.MALFORMED_RESPONSE
                        }
                }
            )
        }
}

private fun InventoryAcquireRequest.remote(): RemoteInventoryAcquireRequest {
    val canonical =
        "{\"expectedCatalogRevision\":${expectedCatalogRevision.value},\"itemId\":\"${itemId.value}\",\"ownerUid\":\"${accountId.value}\"}"
    val hash =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    return RemoteInventoryAcquireRequest(
        accountId,
        itemId,
        expectedCatalogRevision,
        operationId,
        hash,
    )
}

private fun validateReceipt(
    request: RemoteInventoryAcquireRequest,
    accountId: AccountId,
    itemId: ItemId,
    catalogRevision: Revision,
) {
    require(
        accountId == request.accountId &&
            itemId == request.itemId &&
            catalogRevision == request.expectedCatalogRevision
    )
}

private fun RemoteInventoryAcquireResult.terminal(
    request: RemoteInventoryAcquireRequest
): InventoryAcquireResult =
    when (this) {
        is RemoteInventoryAcquireResult.Acquired -> {
            validateReceipt(request, accountId, itemId, catalogRevision)
            InventoryAcquireResult.Success(receipt())
        }
        is RemoteInventoryAcquireResult.AlreadyOwned -> {
            validateReceipt(request, accountId, itemId, catalogRevision)
            InventoryAcquireResult.AlreadyOwned(receipt())
        }
        is RemoteInventoryAcquireResult.ConditionNotMet -> {
            validateReceipt(request, accountId, itemId, catalogRevision)
            InventoryAcquireResult.ConditionNotMet(itemId, catalogRevision, condition)
        }
    }

private fun InventoryAcquireResult.encode(): String =
    when (this) {
        is InventoryAcquireResult.Success -> receipt.encode("ACQUIRED")
        is InventoryAcquireResult.AlreadyOwned -> receipt.encode("ALREADY_OWNED")
        is InventoryAcquireResult.ConditionNotMet ->
            listOf(
                    "CONDITION_NOT_MET",
                    itemId.value,
                    catalogRevision.value.toString(),
                    condition.wireValue,
                )
                .joinToString("|")
        is InventoryAcquireResult.Failure,
        InventoryAcquireResult.Forbidden -> error("Only terminal acquisition results are persisted")
    }

private fun InventoryOwnershipReceipt.encode(kind: String): String =
    listOf(
            kind,
            accountId.value,
            itemId.value,
            catalogRevision.value.toString(),
            ownershipRevision.value.toString(),
            acquiredAt.toEpochMilli().toString(),
            mediaIdentity.path,
            mediaIdentity.sha256,
            mediaIdentity.byteSize.toString(),
            mediaIdentity.mimeType,
            mediaIdentity.width.toString(),
            mediaIdentity.height.toString(),
            mediaIdentity.mediaRevision.value.toString(),
        )
        .joinToString("|")

private fun String.decodeTerminal(request: RemoteInventoryAcquireRequest): InventoryAcquireResult? =
    runCatching {
        val fields = split('|')
        when (fields.firstOrNull()) {
            "ACQUIRED",
            "ALREADY_OWNED" -> {
                require(fields.size == 13)
                val receipt =
                    InventoryOwnershipReceipt(
                        AccountId(fields[1]),
                        ItemId(fields[2]),
                        Revision(fields[3].toLong()),
                        Revision(fields[4].toLong()),
                        Instant.ofEpochMilli(fields[5].toLong()),
                        CatalogMediaIdentity(
                            fields[6],
                            fields[7],
                            fields[8].toLong(),
                            fields[9],
                            fields[10].toInt(),
                            fields[11].toInt(),
                            Revision(fields[12].toLong()),
                        ),
                    )
                validateReceipt(
                    request,
                    receipt.accountId,
                    receipt.itemId,
                    receipt.catalogRevision,
                )
                if (fields[0] == "ACQUIRED") InventoryAcquireResult.Success(receipt)
                else InventoryAcquireResult.AlreadyOwned(receipt)
            }
            "CONDITION_NOT_MET" -> {
                require(fields.size == 4)
                val itemId = ItemId(fields[1])
                val revision = Revision(fields[2].toLong())
                validateReceipt(request, request.accountId, itemId, revision)
                InventoryAcquireResult.ConditionNotMet(
                    itemId,
                    revision,
                    AcquisitionCondition.parse(fields[3]),
                )
            }
            else -> error("Unknown persisted acquisition terminal")
        }
    }
    .getOrNull()

private fun RemoteInventoryAcquireRequest.operationEntity() =
    InventoryAcquisitionOperationEntity(
        accountId.value,
        operationId.value,
        itemId.value,
        expectedCatalogRevision.value,
        requestHash,
        0,
    )

private fun RemoteInventoryAcquireRequest.databaseFailure() =
    InventoryAcquireResult.Failure(InventoryFailure.DATABASE, operationId)

private fun RemoteInventoryAcquireRequest.idempotencyMismatch() =
    InventoryAcquireResult.Failure(InventoryFailure.IDEMPOTENCY_MISMATCH, operationId)

private fun RemoteInventoryAcquireResult.Acquired.receipt() =
    InventoryOwnershipReceipt(
        accountId,
        itemId,
        catalogRevision,
        ownershipRevision,
        acquiredAt,
        mediaIdentity,
    )

private fun RemoteInventoryAcquireResult.AlreadyOwned.receipt() =
    InventoryOwnershipReceipt(
        accountId,
        itemId,
        catalogRevision,
        ownershipRevision,
        acquiredAt,
        mediaIdentity,
    )

private fun InventoryAcquisitionOperationEntity.sameCommand(
    other: InventoryAcquisitionOperationEntity
): Boolean =
    accountId == other.accountId &&
        operationId == other.operationId &&
        itemId == other.itemId &&
        expectedCatalogRevision == other.expectedCatalogRevision &&
        requestHash == other.requestHash

private fun InventorySnapshot.authoritativeWrite() =
    AuthoritativeInventoryCacheWrite(
        accountId = accountId.value,
        generation = generation,
        snapshotHash = snapshotHash,
        registeredPlantCount = registeredPlantCount,
        loadedAtEpochMillis = loadedAt.toEpochMilli(),
        partial = partial,
        catalog = catalog.map { it.cached(accountId) },
        owned = owned.map { it.cached(accountId) },
    )

private fun AuthoritativeInventory.inventorySnapshot() =
    InventorySnapshot(
        accountId = accountId,
        catalog = catalog.map(AuthoritativeCatalogItem::inventoryItem),
        owned = owned.map(AuthoritativeOwnedItem::ownedInventoryItem),
        registeredPlantCount = registeredPlantCount,
        loadedAt = Instant.ofEpochMilli(loadedAtEpochMillis),
        partial = partial,
        generation = generation,
        snapshotHash = snapshotHash,
        verified = true,
    )

private fun InventoryItem.cached(accountId: AccountId) =
    CachedShopItemEntity(
        accountId = accountId.value,
        itemId = id.value,
        name = name,
        description = description,
        category = category.name,
        assetPath = mediaIdentity.path,
        assetSha256 = mediaIdentity.sha256,
        assetByteSize = mediaIdentity.byteSize,
        assetMimeType = mediaIdentity.mimeType,
        assetWidth = mediaIdentity.width,
        assetHeight = mediaIdentity.height,
        assetMediaRevision = mediaIdentity.mediaRevision.value,
        acquisitionCondition = acquisitionCondition?.wireValue,
        revision = revision.value,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
    )

private fun OwnedInventoryItem.cached(accountId: AccountId) =
    CachedOwnedItemEntity(
        accountId = accountId.value,
        itemId = itemId.value,
        acquiredAtEpochMillis = acquiredAt.toEpochMilli(),
        applied = applied,
        revision = revision.value,
        availability = availability.name,
        nameSnapshot = catalogSnapshot?.name,
        categorySnapshot = catalogSnapshot?.category?.name,
        assetPathSnapshot = catalogSnapshot?.mediaIdentity?.path,
        assetSha256Snapshot = catalogSnapshot?.mediaIdentity?.sha256,
        assetByteSizeSnapshot = catalogSnapshot?.mediaIdentity?.byteSize,
        assetMimeTypeSnapshot = catalogSnapshot?.mediaIdentity?.mimeType,
        assetWidthSnapshot = catalogSnapshot?.mediaIdentity?.width,
        assetHeightSnapshot = catalogSnapshot?.mediaIdentity?.height,
        assetMediaRevisionSnapshot = catalogSnapshot?.mediaIdentity?.mediaRevision?.value,
        catalogRevisionSnapshot = catalogSnapshot?.catalogRevision?.value,
    )

private val ACQUISITION_RECEIPT_FIELDS =
    setOf(
        "kind",
        "ownerUid",
        "itemId",
        "catalogRevision",
        "ownershipRevision",
        "acquiredAtEpochMillis",
        "mediaIdentity",
    )
private val CONDITION_NOT_MET_FIELDS =
    setOf("kind", "ownerUid", "itemId", "catalogRevision", "condition")
private val MEDIA_IDENTITY_FIELDS =
    setOf("path", "sha256", "byteSize", "mimeType", "width", "height", "mediaRevision")

private fun Map<*, *>.requireExactFields(expected: Set<String>) {
    if (keys.any { it !is String } || keys != expected) {
        throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
    }
}

private fun Map<*, *>.requiredString(field: String): String =
    (this[field] as? String)?.takeIf { it.isNotEmpty() }
        ?: throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)

private fun Map<*, *>.requiredMediaIdentity(itemId: ItemId): CatalogMediaIdentity {
    val value =
        this["mediaIdentity"] as? Map<*, *>
            ?: throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
    value.requireExactFields(MEDIA_IDENTITY_FIELDS)
    return try {
        CatalogMediaIdentity(
                path = value.requiredString("path"),
                sha256 = value.requiredString("sha256"),
                byteSize = value.requiredLong("byteSize"),
                mimeType = value.requiredString("mimeType"),
                width = value.requiredLong("width").toInt(),
                height = value.requiredLong("height").toInt(),
                mediaRevision = Revision(value.requiredLong("mediaRevision")),
            )
            .also {
                if (!it.path.startsWith("catalog-assets/${itemId.value}/")) {
                    throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
                }
            }
    } catch (error: InventoryRemoteException) {
        throw error
    } catch (_: Exception) {
        throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
    }
}

private fun Map<*, *>.requiredLong(field: String): Long {
    val number =
        this[field] as? Number
            ?: throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
    val value = number.toLong()
    if (number.toDouble() != value.toDouble() || value < 0 || value > 9_007_199_254_740_991L) {
        throw InventoryRemoteException(InventoryFailure.MALFORMED_RESPONSE)
    }
    return value
}

private fun AuthoritativeCatalogItem.inventoryItem() =
    InventoryItem(
        itemId,
        name,
        description,
        category,
        mediaIdentity,
        when (acquisitionCondition) {
            null -> null
            AuthoritativeInventoryCondition.REGISTERED_PLANT ->
                AcquisitionCondition.REGISTERED_PLANT
        },
        revision,
        Instant.ofEpochMilli(updatedAtEpochMillis),
    )

private fun AuthoritativeOwnedItem.ownedInventoryItem() =
    OwnedInventoryItem(
        itemId,
        Instant.ofEpochMilli(acquiredAtEpochMillis),
        applied,
        revision,
        when (availability) {
            AuthoritativeInventoryAvailability.AVAILABLE -> InventoryItemAvailability.AVAILABLE
            AuthoritativeInventoryAvailability.UNAVAILABLE -> InventoryItemAvailability.UNAVAILABLE
        },
        catalogSnapshot?.let {
            OwnedCatalogSnapshot(it.name, it.category, it.mediaIdentity, it.catalogRevision)
        },
    )

private fun InventoryAcquisitionOperationEntity.terminalReceipt():
    InventoryAcquisitionTerminalReceipt? {
    if (state != "COMPLETED") return null
    val encodedResult = result ?: return null
    val request =
        RemoteInventoryAcquireRequest(
            AccountId(accountId),
            ItemId(itemId),
            Revision(expectedCatalogRevision),
            OperationId(operationId),
            requestHash,
        )
    return encodedResult
        .decodeTerminal(request)
        ?.terminalReceipt(request)
        ?.copy(createdAtEpochMillis = createdAtEpochMillis)
}

private fun InventoryAcquisitionTerminalReceipt.sameReceipt(
    other: InventoryAcquisitionTerminalReceipt
): Boolean =
    owner == other.owner &&
        itemId == other.itemId &&
        operationId == other.operationId &&
        kind == other.kind &&
        receipt == other.receipt &&
        receiptId == other.receiptId

private fun InventoryAcquisitionOperationEntity.matches(
    claimant: InventoryReceiptClaimant
): Boolean =
    feedbackClaimToken == claimant.presentationToken &&
        feedbackClaimControllerEpoch == claimant.controllerEpoch &&
        feedbackClaimGeneration == claimant.generation

private fun InventoryAcquisitionOperationEntity.claim(
    receipt: InventoryAcquisitionTerminalReceipt,
    claimant: InventoryReceiptClaimant,
) =
    InventoryReceiptClaim(
        receipt,
        claimant,
        feedbackRowVersion,
        requireNotNull(feedbackClaimLeaseExpiresAtEpochMillis),
        when (feedbackDeliveryState) {
            "ACK_PENDING" -> InventoryReceiptDeliveryPhase.ACK_PENDING
            "PRESENTED" -> InventoryReceiptDeliveryPhase.PRESENTED
            else -> InventoryReceiptDeliveryPhase.CLAIMED
        },
    )

private fun InventoryAcquireResult.terminalReceipt(
    request: RemoteInventoryAcquireRequest
): InventoryAcquisitionTerminalReceipt? =
    when (this) {
        is InventoryAcquireResult.Success ->
            InventoryAcquisitionTerminalReceipt(
                request.accountId,
                request.itemId,
                request.operationId,
                InventoryOwnershipReceiptKind.ACQUIRED,
                receipt,
            )
        is InventoryAcquireResult.AlreadyOwned ->
            InventoryAcquisitionTerminalReceipt(
                request.accountId,
                request.itemId,
                request.operationId,
                InventoryOwnershipReceiptKind.ALREADY_OWNED,
                receipt,
            )
        is InventoryAcquireResult.ConditionNotMet,
        is InventoryAcquireResult.Failure,
        InventoryAcquireResult.Forbidden -> null
    }

private fun InventorySnapshot.loadResult(
    stale: Boolean,
    receiptCandidates: List<InventoryReceiptId> = emptyList(),
    receiptCandidatesAuthoritative: Boolean = true,
    refreshRequired: Boolean = false,
): InventoryLoadResult =
    if (partial) {
        InventoryLoadResult.Partial(
            this,
            stale,
            receiptCandidates,
            receiptCandidatesAuthoritative,
            refreshRequired,
        )
    } else {
        InventoryLoadResult.Ready(
            this,
            stale,
            receiptCandidates,
            receiptCandidatesAuthoritative,
            refreshRequired,
        )
    }

private fun failureOrMalformed(value: String): InventoryFailure = runCatching {
    InventoryFailure.valueOf(value)
}
    .getOrDefault(InventoryFailure.MALFORMED_RESPONSE)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
