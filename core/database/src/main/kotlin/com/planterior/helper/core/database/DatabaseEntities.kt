package com.planterior.helper.core.database

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import java.time.LocalDate

@Entity(
    tableName = "cached_plants",
    primaryKeys = ["accountId", "plantId"],
    indices = [Index("accountId")],
)
data class CachedPlantEntity(
    val accountId: String,
    val plantId: String,
    val displayName: String,
    val representativePhotoPath: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "cached_watering_schedules",
    primaryKeys = ["accountId", "scheduleId"],
    indices = [Index(value = ["accountId", "plantId"])],
)
data class CachedWateringScheduleEntity(
    val accountId: String,
    val scheduleId: String,
    val plantId: String,
    val dueDate: String,
    val reminderTime: String,
    val zoneId: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
) {
    @Ignore val dueLocalDate: LocalDate = LocalDate.parse(dueDate)
}

@Entity(
    tableName = "operation_outbox",
    indices = [Index(value = ["accountId", "state", "createdAtEpochMillis"])],
)
data class OperationOutboxEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val accountId: String,
    val aggregateType: String,
    val aggregateId: String,
    val mutationType: String,
    val expectedRevision: Long,
    val draftPayload: String,
    val createdAtEpochMillis: Long,
    val state: String = "PENDING",
    val actualRevision: Long? = null,
    val lastErrorCode: String? = null,
)

@Entity(tableName = "last_sync", primaryKeys = ["accountId", "domain"])
data class LastSyncEntity(
    val accountId: String,
    val domain: String,
    val syncedAtEpochMillis: Long,
    val status: String,
    val errorCode: String?,
)
