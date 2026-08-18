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
    val contentId: String? = null,
    val registrationMethod: String = "MANUAL",
    val location: String? = null,
    val note: String? = null,
    val lastWateredDate: String? = null,
    val detailsComplete: Boolean = true,
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
    primaryKeys = ["accountId", "operationId"],
    indices = [Index(value = ["accountId", "state", "createdAtEpochMillis"])],
)
data class OperationOutboxEntity(
    val operationId: String,
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

/**
 * 마지막으로 서버에 확정된 미니홈피 구성이다.
 *
 * 홈은 미리보기에 이름과 배치된 식물 수만 쓰므로 좌표나 아이템 목록까지 캐시하지 않는다. 저장하지 않은 draft는 이 테이블에 들어오지 않고, 계정당 하나만 존재한다.
 */
@Entity(tableName = "cached_mini_homes", primaryKeys = ["accountId"])
data class CachedMiniHomeEntity(
    val accountId: String,
    val miniHomeId: String,
    val name: String,
    val placedPlantCount: Int,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "last_sync", primaryKeys = ["accountId", "domain"])
data class LastSyncEntity(
    val accountId: String,
    val domain: String,
    val syncedAtEpochMillis: Long,
    val status: String,
    val errorCode: String?,
)
