package com.planterior.helper.core.data

import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.core.model.Revision
import java.time.Instant

class OfflineFirstSyncRepository(
    private val database: PlanteriorDatabase,
    private val gateway: RemoteMutationGateway,
    private val now: () -> Instant = Instant::now,
) {
    private var activeAccount: AccountId? = null

    fun activate(accountId: AccountId) {
        activeAccount = accountId
    }

    suspend fun visiblePlants(): List<PersonalPlant> {
        val account = checkNotNull(activeAccount) { "No active account" }
        return database.cacheDao().plants(account.value).map {
            PersonalPlant(
                PersonalPlantId(it.plantId),
                it.displayName,
                null,
                RegistrationMethod.MANUAL,
                it.representativePhotoPath,
                null,
                null,
                null,
                Revision(it.revision),
                Instant.ofEpochMilli(it.updatedAtEpochMillis),
            )
        }
    }

    suspend fun enqueuePlantDraft(
        accountId: AccountId,
        operationId: OperationId,
        plantId: String,
        expectedRevision: Long,
        draftPayload: String,
    ) {
        require(expectedRevision >= 0)
        database
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    operationId.value,
                    accountId.value,
                    "personalPlant",
                    plantId,
                    "UPDATE",
                    expectedRevision,
                    draftPayload,
                    now().toEpochMilli(),
                )
            )
    }

    suspend fun sync(accountId: AccountId): SyncReport {
        var applied = 0
        var conflicts = 0
        var failed = 0
        for (operation in database.syncDao().ready(accountId.value)) {
            when (
                val result =
                    gateway.apply(
                        RemoteMutationCommand(
                            accountId,
                            OperationId(operation.operationId),
                            operation.aggregateType,
                            operation.aggregateId,
                            Revision(operation.expectedRevision),
                            operation.draftPayload,
                        )
                    )
            ) {
                is RemoteMutationResult.Applied,
                is RemoteMutationResult.Duplicate -> {
                    database.syncDao().remove(accountId.value, operation.operationId)
                    applied += 1
                }
                is RemoteMutationResult.Conflict -> {
                    database
                        .syncDao()
                        .markConflict(accountId.value, operation.operationId, result.actualRevision)
                    conflicts += 1
                }
                is RemoteMutationResult.Failed -> {
                    database
                        .syncDao()
                        .markFailed(accountId.value, operation.operationId, result.code)
                    failed += 1
                }
            }
        }
        return SyncReport(applied, conflicts, failed)
    }
}

data class SyncReport(val applied: Int, val conflicts: Int, val failed: Int)
