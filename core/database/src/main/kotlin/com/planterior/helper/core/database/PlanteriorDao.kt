package com.planterior.helper.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlant(entity: CachedPlantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(entity: CachedWateringScheduleEntity)

    @Query(
        "SELECT * FROM cached_plants WHERE accountId = :accountId ORDER BY updatedAtEpochMillis DESC, plantId ASC"
    )
    fun observePlants(accountId: String): Flow<List<CachedPlantEntity>>

    @Query(
        "SELECT * FROM cached_plants WHERE accountId = :accountId ORDER BY updatedAtEpochMillis DESC, plantId ASC"
    )
    suspend fun plants(accountId: String): List<CachedPlantEntity>

    @Query("SELECT * FROM cached_plants WHERE accountId = :accountId AND plantId = :plantId")
    fun plantBlocking(accountId: String, plantId: String): CachedPlantEntity?

    @Query(
        "SELECT * FROM cached_watering_schedules WHERE accountId = :accountId AND scheduleId = :scheduleId"
    )
    suspend fun schedule(accountId: String, scheduleId: String): CachedWateringScheduleEntity?

    @Query("DELETE FROM cached_plants WHERE accountId = :accountId")
    suspend fun clearPlants(accountId: String)

    @Query("DELETE FROM cached_watering_schedules WHERE accountId = :accountId")
    suspend fun clearSchedules(accountId: String)

    @Transaction
    suspend fun clearVisibleAccount(accountId: String) {
        clearPlants(accountId)
        clearSchedules(accountId)
    }
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(entity: OperationOutboxEntity): Long

    @Query(
        "UPDATE operation_outbox SET draftPayload = :draftPayload, expectedRevision = :expectedRevision, mutationType = :mutationType, state = 'PENDING', actualRevision = NULL, lastErrorCode = NULL WHERE operationId = :operationId AND accountId = :accountId"
    )
    suspend fun mergeOperation(
        operationId: String,
        accountId: String,
        draftPayload: String,
        expectedRevision: Long,
        mutationType: String,
    )

    @Transaction
    suspend fun enqueue(entity: OperationOutboxEntity) {
        if (insertOperation(entity) == -1L)
            mergeOperation(
                entity.operationId,
                entity.accountId,
                entity.draftPayload,
                entity.expectedRevision,
                entity.mutationType,
            )
    }

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND state = 'PENDING' ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun ready(accountId: String): List<OperationOutboxEntity>

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND state IN ('PENDING', 'CONFLICT', 'FAILED') ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun pending(accountId: String): List<OperationOutboxEntity>

    @Query(
        "DELETE FROM operation_outbox WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun remove(accountId: String, operationId: String)

    @Query(
        "UPDATE operation_outbox SET state = 'CONFLICT', actualRevision = :actualRevision, lastErrorCode = 'REVISION_CONFLICT' WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markConflict(accountId: String, operationId: String, actualRevision: Long)

    @Query(
        "UPDATE operation_outbox SET state = 'FAILED', lastErrorCode = :code WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markFailed(accountId: String, operationId: String, code: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLastSync(entity: LastSyncEntity)

    @Query("SELECT * FROM last_sync WHERE accountId = :accountId AND domain = :domain")
    suspend fun lastSync(accountId: String, domain: String): LastSyncEntity?
}
