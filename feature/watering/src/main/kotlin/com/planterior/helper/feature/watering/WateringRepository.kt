package com.planterior.helper.feature.watering

import androidx.room.withTransaction
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class OutboxWateringRepository(
    private val database: PlanteriorDatabase,
    private val preparationSource: WateringPreparationSource,
    private val remote: WateringRemoteDataSource,
    private val gateway: RemoteMutationGateway,
    private val now: () -> Instant = Instant::now,
) : WateringRepository {
    override suspend fun load(plantId: PersonalPlantId): WateringLoad {
        val account = activeAccountOrNull() ?: return WateringLoad.Forbidden
        val result =
            try {
                preparationSource.load(plantId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WateringLoad.Failed
            }
        if (!isActive(account)) return WateringLoad.Forbidden
        return if (result is WateringLoad.Found && result.snapshot.accountId != account) {
            WateringLoad.Forbidden
        } else {
            result
        }
    }

    override suspend fun complete(request: WateringCompletionRequest): WateringCompletionResult {
        WateringConfirmActionDiagnostics.observe(
            WateringConfirmActionObservation(
                WateringConfirmActionStage.REPOSITORY_COMPLETE_ENTRY,
                request.plantId,
                request.operationId,
            )
        )
        if (activeAccountOrNull() != request.accountId) return WateringCompletionResult.Forbidden
        if (request.wateredDate > now().atZone(request.accountZone).toLocalDate()) {
            return WateringCompletionResult.Failed(WateringCompletionFailure.INCONSISTENT_RECEIPT)
        }
        val payload =
            JsonObject(mapOf("wateredDate" to JsonPrimitive(request.wateredDate.toString())))
                .toString()
        val outbox =
            OperationOutboxEntity(
                operationId = request.operationId.value,
                accountId = request.accountId.value,
                aggregateType = WATERING_COMPLETIONS,
                aggregateId = request.plantId.value,
                mutationType = "UPDATE",
                expectedRevision = request.expectedPlantRevision,
                draftPayload = payload,
                createdAtEpochMillis = now().toEpochMilli(),
            )
        try {
            val existing =
                database.syncDao().operation(request.accountId.value, request.operationId.value)
            if (!isActive(request.accountId)) return WateringCompletionResult.Forbidden
            if (existing != null && !existing.matchesFrozen(outbox)) {
                database
                    .syncDao()
                    .markFailed(
                        request.accountId.value,
                        request.operationId.value,
                        "OUTBOX_MISMATCH",
                    )
                return WateringCompletionResult.Failed(WateringCompletionFailure.OUTBOX_MISMATCH)
            }
            database.syncDao().enqueue(outbox)
            if (!isActive(request.accountId)) return WateringCompletionResult.Forbidden
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return WateringCompletionResult.Failed(WateringCompletionFailure.DATABASE_UNAVAILABLE)
        }
        val mutation =
            try {
                gateway.apply(
                    RemoteMutationCommand(
                        request.accountId,
                        request.operationId,
                        WATERING_COMPLETIONS,
                        request.plantId.value,
                        "UPDATE",
                        Revision(request.expectedPlantRevision),
                        payload,
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RemoteMutationResult.Failed("UNAVAILABLE")
            }
        WateringConfirmActionDiagnostics.observe(
            WateringConfirmActionObservation(
                WateringConfirmActionStage.APPLY_RESULT,
                request.plantId,
                request.operationId,
            )
        )
        val revision =
            when (mutation) {
                is RemoteMutationResult.Applied -> mutation.revision
                is RemoteMutationResult.Duplicate -> mutation.revision
                is RemoteMutationResult.Conflict -> {
                    return markConflict(request, mutation.actualRevision)
                }
                is RemoteMutationResult.Failed -> return classifyFailure(request, mutation.code)
            }
        return reconcileReceipt(
            request,
            revision,
            WateringCompletionFailure.INCONSISTENT_RECEIPT,
        )
    }

    override suspend fun reconcile(request: WateringCompletionRequest): WateringCompletionResult {
        if (activeAccountOrNull() != request.accountId) return WateringCompletionResult.Forbidden
        val frozen =
            try {
                database.syncDao().operation(request.accountId.value, request.operationId.value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return WateringCompletionResult.Failed(
                    WateringCompletionFailure.DATABASE_UNAVAILABLE
                )
            }
        if (frozen == null || !frozen.matchesRequest(request)) {
            return WateringCompletionResult.Failed(
                WateringCompletionFailure.RECONCILIATION_REQUIRED
            )
        }
        return reconcileReceipt(
            request,
            request.expectedPlantRevision + 1,
            WateringCompletionFailure.RECONCILIATION_REQUIRED,
        )
    }

    private suspend fun reconcileReceipt(
        request: WateringCompletionRequest,
        committedRevision: Long,
        missingFailure: WateringCompletionFailure,
    ): WateringCompletionResult {
        if (!isActive(request.accountId)) return WateringCompletionResult.Forbidden
        WateringConfirmActionDiagnostics.observe(
            WateringConfirmActionObservation(
                WateringConfirmActionStage.RECEIPT_LOOKUP_ENTRY,
                request.plantId,
                request.operationId,
            )
        )
        val lookup =
            try {
                remote.receipt(request.accountId, request.plantId, request.operationId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WateringReceiptLookup.Failed
            }
        if (!isActive(request.accountId)) return WateringCompletionResult.Forbidden
        val receipt =
            when (lookup) {
                is WateringReceiptLookup.Found -> lookup.receipt
                WateringReceiptLookup.Forbidden -> return WateringCompletionResult.Forbidden
                WateringReceiptLookup.NotFound,
                WateringReceiptLookup.Failed ->
                    return WateringCompletionResult.Failed(missingFailure)
            }
        if (!receipt.matches(request, committedRevision)) {
            return WateringCompletionResult.Failed(
                WateringCompletionFailure.RECONCILIATION_REQUIRED
            )
        }
        return cacheCommitted(request, receipt)
    }

    private suspend fun cacheCommitted(
        request: WateringCompletionRequest,
        receipt: WateringCompletionReceipt,
    ): WateringCompletionResult =
        try {
            val cachedPlant =
                database.cacheDao().plant(request.accountId.value, request.plantId.value)
                    ?: return WateringCompletionResult.Failed(
                        WateringCompletionFailure.DATABASE_UNAVAILABLE
                    )
            val cachedSchedule =
                database.cacheDao().schedule(request.accountId.value, request.plantId.value)
            if (!isActive(request.accountId)) return WateringCompletionResult.Forbidden
            database.withTransaction {
                ensureActive(request.accountId)
                database
                    .cacheDao()
                    .upsertPlant(
                        cachedPlant.copy(
                            lastWateredDate = receipt.wateredDate.toString(),
                            revision = receipt.plantRevision,
                            updatedAtEpochMillis = receipt.recordedAt.toEpochMilli(),
                        )
                    )
                ensureActive(request.accountId)
                database.cacheDao().upsertSchedule(cachedSchedule.updated(receipt))
                ensureActive(request.accountId)
                database.syncDao().remove(request.accountId.value, request.operationId.value)
                ensureActive(request.accountId)
            }
            WateringCompletionResult.Completed(receipt)
        } catch (error: CancellationException) {
            throw error
        } catch (_: ActiveAccountChangedException) {
            WateringCompletionResult.Forbidden
        } catch (_: Exception) {
            WateringCompletionResult.Failed(WateringCompletionFailure.DATABASE_UNAVAILABLE)
        }

    private suspend fun markConflict(
        request: WateringCompletionRequest,
        actualRevision: Long,
    ): WateringCompletionResult =
        try {
            database
                .syncDao()
                .markConflict(
                    request.accountId.value,
                    request.operationId.value,
                    actualRevision,
                )
            WateringCompletionResult.Failed(WateringCompletionFailure.REVISION_CONFLICT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WateringCompletionResult.Failed(WateringCompletionFailure.DATABASE_UNAVAILABLE)
        }

    private suspend fun classifyFailure(
        request: WateringCompletionRequest,
        code: String,
    ): WateringCompletionResult =
        try {
            val normalized = code.uppercase()
            database
                .syncDao()
                .markFailed(request.accountId.value, request.operationId.value, normalized)
            when (normalized) {
                "PERMISSION_DENIED",
                "UNAUTHENTICATED" -> WateringCompletionResult.Forbidden
                "NOT_FOUND" -> WateringCompletionResult.NotFound
                in TRANSIENT_FAILURE_CODES ->
                    WateringCompletionResult.Failed(WateringCompletionFailure.REMOTE_WRITE_FAILED)
                else ->
                    WateringCompletionResult.Failed(
                        WateringCompletionFailure.RECONCILIATION_REQUIRED
                    )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WateringCompletionResult.Failed(WateringCompletionFailure.DATABASE_UNAVAILABLE)
        }

    private fun activeAccountOrNull(): AccountId? =
        try {
            remote.activeAccount()
        } catch (_: Exception) {
            null
        }

    private fun isActive(accountId: AccountId) = activeAccountOrNull() == accountId

    private fun ensureActive(accountId: AccountId) {
        if (!isActive(accountId)) throw ActiveAccountChangedException()
    }

    private class ActiveAccountChangedException : Exception()

    private companion object {
        const val WATERING_COMPLETIONS = "wateringCompletions"
        val TRANSIENT_FAILURE_CODES =
            setOf(
                "ABORTED",
                "DEADLINE_EXCEEDED",
                "INTERNAL",
                "RESOURCE_EXHAUSTED",
                "UNAVAILABLE",
            )
    }
}

class FirebaseWateringRemoteDataSource(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : WateringRemoteDataSource {
    override fun activeAccount(): AccountId =
        auth.currentUser?.uid?.let(::AccountId) ?: error("Authentication is required")

    override suspend fun receipt(
        accountId: AccountId,
        plantId: PersonalPlantId,
        operationId: OperationId,
    ): WateringReceiptLookup {
        if (activeAccount() != accountId) return WateringReceiptLookup.Forbidden
        return try {
            val operation =
                firestore
                    .document("users/${accountId.value}/operations/${operationId.value}")
                    .get()
                    .await()
            if (activeAccount() != accountId) return WateringReceiptLookup.Forbidden
            if (!operation.exists()) return WateringReceiptLookup.NotFound
            if (operation.getString("ownerUid") != accountId.value) {
                return WateringReceiptLookup.Forbidden
            }
            if (
                operation.getString("idempotencyKey") != operationId.value ||
                    operation.getString("recordId") != operationId.value ||
                    operation.getString("documentPath") !=
                        "users/${accountId.value}/personalPlants/${plantId.value}"
            ) {
                return WateringReceiptLookup.Failed
            }
            WateringReceiptLookup.Found(
                WateringCompletionReceipt(
                    accountId,
                    plantId,
                    operationId,
                    requireNotNull(operation.getString("recordId")),
                    LocalDate.parse(requireNotNull(operation.getString("wateredDate"))),
                    LocalDate.parse(requireNotNull(operation.getString("dueDate"))),
                    requireNotNull(operation.getLong("plantRevision")),
                    requireNotNull(operation.getLong("scheduleRevision")),
                    requireNotNull(operation.get("recordedAt") as? Timestamp).toDate().toInstant(),
                    ZoneId.of(requireNotNull(operation.getString("zoneId"))),
                    requireNotNull(operation.getString("requestHash")),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFirestoreException) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                WateringReceiptLookup.Forbidden
            } else {
                WateringReceiptLookup.Failed
            }
        } catch (_: Exception) {
            WateringReceiptLookup.Failed
        }
    }
}

private fun OperationOutboxEntity.matchesFrozen(other: OperationOutboxEntity): Boolean =
    accountId == other.accountId &&
        operationId == other.operationId &&
        aggregateType == other.aggregateType &&
        aggregateId == other.aggregateId &&
        mutationType == other.mutationType &&
        expectedRevision == other.expectedRevision &&
        draftPayload == other.draftPayload

private fun OperationOutboxEntity.matchesRequest(request: WateringCompletionRequest): Boolean =
    accountId == request.accountId.value &&
        operationId == request.operationId.value &&
        aggregateType == "wateringCompletions" &&
        aggregateId == request.plantId.value &&
        mutationType == "UPDATE" &&
        expectedRevision == request.expectedPlantRevision &&
        runCatching { Json.parseToJsonElement(draftPayload) }
            .getOrNull()
            ?.let { payload ->
                (payload as? JsonObject)?.get("wateredDate") ==
                    JsonPrimitive(request.wateredDate.toString())
            } == true

private fun WateringCompletionReceipt.matches(
    request: WateringCompletionRequest,
    committedRevision: Long,
): Boolean =
    accountId == request.accountId &&
        plantId == request.plantId &&
        operationId == request.operationId &&
        recordId == request.operationId.value &&
        wateredDate == request.wateredDate &&
        nextDueDate > wateredDate &&
        plantRevision == committedRevision &&
        scheduleRevision >= 1 &&
        requestHash == WateringRequestHash.calculate(request)

private fun CachedWateringScheduleEntity?.updated(
    receipt: WateringCompletionReceipt
): CachedWateringScheduleEntity {
    if (this != null) {
        return copy(
            dueDate = receipt.nextDueDate.toString(),
            zoneId = receipt.accountZone.id,
            revision = receipt.scheduleRevision,
            updatedAtEpochMillis = receipt.recordedAt.toEpochMilli(),
        )
    }
    return CachedWateringScheduleEntity(
        accountId = receipt.accountId.value,
        scheduleId = receipt.plantId.value,
        plantId = receipt.plantId.value,
        dueDate = receipt.nextDueDate.toString(),
        reminderTime = null,
        zoneId = receipt.accountZone.id,
        revision = receipt.scheduleRevision,
        updatedAtEpochMillis = receipt.recordedAt.toEpochMilli(),
        enabled = null,
    )
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}
