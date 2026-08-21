package com.planterior.helper.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import java.time.LocalDate
import java.util.UUID

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
    val reminderTime: String?,
    val zoneId: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val enabled: Boolean? = null,
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
    val failureDetails: String? = null,
    val committedOperationId: String? = null,
    val committedExpectedRevision: Long? = null,
    val committedRevision: Long? = null,
    val payloadHash: String? = null,
    val committedPayloadHash: String? = null,
    val lineageId: String? = null,
    val supersedesOperationId: String? = null,
    @ColumnInfo(defaultValue = "''") val rowHandleId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val rowVersion: Long = 0,
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

/** 마지막 서버 확정 revision에 속한 미니홈피 배치. draft는 operation_outbox에만 둔다. */
@Entity(
    tableName = "cached_mini_home_placements",
    primaryKeys = ["accountId", "placementId"],
    indices = [Index(value = ["accountId", "miniHomeId", "layoutRevision", "zIndex"])],
)
data class CachedMiniHomePlacementEntity(
    val accountId: String,
    val placementId: String,
    val miniHomeId: String,
    val plantId: String?,
    val itemId: String?,
    val normalizedX: Double,
    val normalizedY: Double,
    val zIndex: Int,
    val layoutRevision: Long,
)

/** Owner-scoped ordering identity for the last authoritative layout or deletion applied to Room. */
@Entity(tableName = "mini_home_cache_watermarks", primaryKeys = ["accountId"])
data class MiniHomeCacheWatermarkEntity(
    val accountId: String,
    val generation: Long,
    val kind: String,
    val layoutRevision: Long?,
    val miniHomeId: String?,
    val operationId: String?,
    val payloadHash: String?,
    val tombstoneId: String?,
    val authoritativeAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "1") val verified: Boolean,
    val snapshotToken: String? = null,
    val snapshotGeneration: Long? = null,
)

enum class MiniHomeCacheWatermarkKind {
    PRESENT,
    DELETED,
}

data class MiniHomeCacheWatermark(
    val accountId: String,
    val generation: Long,
    val kind: MiniHomeCacheWatermarkKind,
    val layoutRevision: Long?,
    val miniHomeId: String?,
    val operationId: String?,
    val payloadHash: String?,
    val tombstoneId: String?,
    val authoritativeAtEpochMillis: Long,
    val verified: Boolean,
    val snapshotToken: String? = null,
    val snapshotGeneration: Long? = null,
)

data class CachedMiniHomeLayoutState(
    val watermark: MiniHomeCacheWatermark,
    val home: CachedMiniHomeEntity?,
    val placements: List<CachedMiniHomePlacementEntity>,
)

data class CachedMiniHomeSnapshotState(
    val layout: CachedMiniHomeLayoutState?,
    val inventory: CachedInventoryState?,
    val coherent: Boolean,
)

sealed interface AuthoritativeMiniHomeCacheWrite {
    val accountId: String
    val generation: Long
    val authoritativeAtEpochMillis: Long
    val snapshotToken: String?
    val snapshotGeneration: Long?

    data class Layout(
        override val accountId: String,
        override val generation: Long,
        val operationId: String,
        val payloadHash: String,
        val home: CachedMiniHomeEntity,
        val placements: List<CachedMiniHomePlacementEntity>,
        override val snapshotToken: String? = null,
        override val snapshotGeneration: Long? = null,
    ) : AuthoritativeMiniHomeCacheWrite {
        override val authoritativeAtEpochMillis: Long = home.updatedAtEpochMillis
    }

    data class Deletion(
        override val accountId: String,
        override val generation: Long,
        val tombstoneId: String,
        val deletedAtEpochMillis: Long,
        override val snapshotToken: String? = null,
        override val snapshotGeneration: Long? = null,
    ) : AuthoritativeMiniHomeCacheWrite {
        override val authoritativeAtEpochMillis: Long = deletedAtEpochMillis
    }
}

sealed interface MiniHomeCacheApplyResult {
    val current: CachedMiniHomeLayoutState

    data class Applied(override val current: CachedMiniHomeLayoutState) : MiniHomeCacheApplyResult

    data class Ignored(override val current: CachedMiniHomeLayoutState) : MiniHomeCacheApplyResult

    data class Conflict(override val current: CachedMiniHomeLayoutState) : MiniHomeCacheApplyResult
}

@Entity(
    tableName = "cached_shop_items",
    primaryKeys = ["accountId", "itemId"],
    indices = [Index(value = ["accountId", "category", "name", "itemId"])],
)
data class CachedShopItemEntity(
    val accountId: String,
    val itemId: String,
    val name: String,
    val description: String,
    val category: String,
    val assetPath: String,
    val acquisitionCondition: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val assetSha256: String = "",
    val assetByteSize: Long = 0,
    val assetMimeType: String = "",
    val assetWidth: Int = 0,
    val assetHeight: Int = 0,
    val assetMediaRevision: Long = 0,
)

@Entity(
    tableName = "cached_owned_items",
    primaryKeys = ["accountId", "itemId"],
    indices = [Index(value = ["accountId", "applied", "acquiredAtEpochMillis"])],
)
data class CachedOwnedItemEntity(
    val accountId: String,
    val itemId: String,
    val acquiredAtEpochMillis: Long,
    val applied: Boolean,
    val revision: Long,
    val availability: String = "AVAILABLE",
    val nameSnapshot: String? = null,
    val categorySnapshot: String? = null,
    val assetPathSnapshot: String? = null,
    val catalogRevisionSnapshot: Long? = null,
    val assetSha256Snapshot: String? = null,
    val assetByteSizeSnapshot: Long? = null,
    val assetMimeTypeSnapshot: String? = null,
    val assetWidthSnapshot: Int? = null,
    val assetHeightSnapshot: Int? = null,
    val assetMediaRevisionSnapshot: Long? = null,
)

/** Durable owner-scoped ordering identity for a complete authoritative inventory snapshot. */
@Entity(tableName = "inventory_snapshot_watermarks", primaryKeys = ["accountId"])
data class InventorySnapshotWatermarkEntity(
    val accountId: String,
    val generation: Long,
    val snapshotHash: String,
    val registeredPlantCount: Int,
    val loadedAtEpochMillis: Long,
    val partial: Boolean,
    @ColumnInfo(defaultValue = "1") val verified: Boolean,
    val snapshotToken: String? = null,
    val snapshotGeneration: Long? = null,
)

data class CachedInventoryState(
    val watermark: InventorySnapshotWatermarkEntity,
    val catalog: List<CachedShopItemEntity>,
    val owned: List<CachedOwnedItemEntity>,
)

data class AuthoritativeInventoryCacheWrite(
    val accountId: String,
    val generation: Long,
    val snapshotHash: String,
    val registeredPlantCount: Int,
    val loadedAtEpochMillis: Long,
    val partial: Boolean,
    val catalog: List<CachedShopItemEntity>,
    val owned: List<CachedOwnedItemEntity>,
    val snapshotToken: String? = null,
    val snapshotGeneration: Long? = null,
)

sealed interface InventoryCacheApplyResult {
    val current: CachedInventoryState

    data class Applied(override val current: CachedInventoryState) : InventoryCacheApplyResult

    data class Ignored(override val current: CachedInventoryState) : InventoryCacheApplyResult

    data class Conflict(override val current: CachedInventoryState) : InventoryCacheApplyResult
}

@Entity(
    tableName = "inventory_acquisition_operations",
    primaryKeys = ["accountId", "operationId"],
    indices =
        [
            Index(value = ["accountId", "state", "createdAtEpochMillis"]),
            Index(
                value =
                    ["accountId", "feedbackDeliveryState", "createdAtEpochMillis", "operationId"]
            ),
        ],
)
data class InventoryAcquisitionOperationEntity(
    val accountId: String,
    val operationId: String,
    val itemId: String,
    val expectedCatalogRevision: Long,
    val requestHash: String,
    val createdAtEpochMillis: Long,
    val state: String = "PENDING",
    val result: String? = null,
    val lastErrorCode: String? = null,
    val feedbackDeliveryState: String = "NONE",
    val feedbackAcknowledgedAtEpochMillis: Long? = null,
    val feedbackClaimToken: String? = null,
    val feedbackClaimControllerEpoch: Long? = null,
    val feedbackClaimGeneration: Long? = null,
    val feedbackClaimLeaseExpiresAtEpochMillis: Long? = null,
    val feedbackRowVersion: Long = 0,
)

@Entity(tableName = "last_sync", primaryKeys = ["accountId", "domain"])
data class LastSyncEntity(
    val accountId: String,
    val domain: String,
    val syncedAtEpochMillis: Long,
    val status: String,
    val errorCode: String?,
)
