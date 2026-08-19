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
    suspend fun plant(accountId: String, plantId: String): CachedPlantEntity?

    @Query(
        "SELECT * FROM cached_watering_schedules WHERE accountId = :accountId AND scheduleId = :scheduleId"
    )
    suspend fun schedule(accountId: String, scheduleId: String): CachedWateringScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMiniHome(entity: CachedMiniHomeEntity)

    @Query("SELECT * FROM cached_mini_homes WHERE accountId = :accountId")
    suspend fun miniHome(accountId: String): CachedMiniHomeEntity?

    @Query("DELETE FROM cached_mini_homes WHERE accountId = :accountId")
    suspend fun clearMiniHome(accountId: String)

    /**
     * 서버가 확정한 미니홈피 구성을 캐시에 반영한다.
     *
     * @param remote 서버 구성. 서버에서 삭제되었으면 `null`이며 이때 캐시도 비워 삭제된 방을 계속 보여주지 않는다.
     */
    @Transaction
    suspend fun reconcileMiniHome(accountId: String, remote: CachedMiniHomeEntity?) {
        if (remote == null) clearMiniHome(accountId) else upsertMiniHome(remote)
    }

    @Query("DELETE FROM cached_plants WHERE accountId = :accountId")
    suspend fun clearPlants(accountId: String)

    @Query("DELETE FROM cached_watering_schedules WHERE accountId = :accountId")
    suspend fun clearSchedules(accountId: String)

    @Query("SELECT plantId FROM cached_plants WHERE accountId = :accountId")
    suspend fun plantIds(accountId: String): List<String>

    @Query("SELECT scheduleId FROM cached_watering_schedules WHERE accountId = :accountId")
    suspend fun scheduleIds(accountId: String): List<String>

    @Query(
        "DELETE FROM cached_plants WHERE accountId = :accountId AND plantId = :plantId AND NOT EXISTS (SELECT 1 FROM operation_outbox WHERE accountId = :accountId AND aggregateType IN ('personalPlant', 'personalPlants') AND aggregateId = :plantId AND state IN ('PENDING', 'CONFLICT', 'FAILED'))"
    )
    suspend fun deleteRemoteMissingPlantUnlessDraft(accountId: String, plantId: String)

    @Query(
        "DELETE FROM cached_watering_schedules WHERE accountId = :accountId AND scheduleId = :scheduleId AND NOT EXISTS (SELECT 1 FROM operation_outbox WHERE accountId = :accountId AND aggregateType = 'wateringSchedule' AND aggregateId = :scheduleId AND state IN ('PENDING', 'CONFLICT', 'FAILED'))"
    )
    suspend fun deleteRemoteMissingScheduleUnlessDraft(accountId: String, scheduleId: String)

    @Transaction
    suspend fun reconcilePlants(accountId: String, remote: List<CachedPlantEntity>) {
        remote.forEach { upsertPlant(it) }
        val remoteIds = remote.mapTo(mutableSetOf()) { it.plantId }
        plantIds(accountId).filterNot(remoteIds::contains).forEach {
            deleteRemoteMissingPlantUnlessDraft(accountId, it)
        }
    }

    @Transaction
    suspend fun reconcileSchedules(
        accountId: String,
        remote: List<CachedWateringScheduleEntity>,
    ) {
        remote.forEach { upsertSchedule(it) }
        val remoteIds = remote.mapTo(mutableSetOf()) { it.scheduleId }
        scheduleIds(accountId).filterNot(remoteIds::contains).forEach {
            deleteRemoteMissingScheduleUnlessDraft(accountId, it)
        }
    }

    @Transaction
    suspend fun clearVisibleAccount(accountId: String) {
        clearPlants(accountId)
        clearSchedules(accountId)
        clearMiniHome(accountId)
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
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun operation(accountId: String, operationId: String): OperationOutboxEntity?

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND state = 'PENDING' ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun ready(accountId: String): List<OperationOutboxEntity>

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND (state = 'PENDING' OR (state = 'FAILED' AND lastErrorCode IN (:transientCodes))) ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun replayable(
        accountId: String,
        transientCodes: Set<String>,
    ): List<OperationOutboxEntity>

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
