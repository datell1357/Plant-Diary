package com.planterior.helper.core.data

import com.google.firebase.Timestamp
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.time.Instant

object FirestoreContract {
    const val USERS = "users"
    const val PLANT_CONTENTS = "plantContents"
    const val RISK_CONTENTS = "riskContents"
    const val SHOP_ITEMS = "shopItems"
    const val PUBLIC_SHARES = "publicShares"
    const val AUDIT_LOGS = "auditLogs"

    enum class UserCollection(val segment: String) {
        PERSONAL_PLANTS("personalPlants"),
        WATERING_RECORDS("wateringRecords"),
        WATERING_SCHEDULES("wateringSchedules"),
        NOTIFICATION_SETTINGS("notificationSettings"),
        NOTIFICATION_PLANT_SETTINGS("notificationPlantSettings"),
        NOTIFICATION_HISTORY("notificationHistory"),
        WEATHER_SETTINGS("weatherSettings"),
        WEATHER_PLANT_SETTINGS("weatherPlantSettings"),
        WEATHER_SNAPSHOTS("weatherSnapshots"),
        WEATHER_ALERTS("weatherAlerts"),
        WEATHER_RISKS("weatherRisks"),
        MINI_HOMES("miniHomes"),
        PLACEMENTS("placements"),
        OWNED_ITEMS("ownedItems"),
        SHARE_LINKS("shareLinks"),
        CONSENTS("consents"),
        DELETION_REQUESTS("deletionRequests"),
        NOTIFICATION_DELIVERIES("notificationDeliveries"),
        IDENTIFICATION_REQUESTS("identificationRequests"),
        OPERATIONS("operations"),
    }

    fun userRoot(accountId: AccountId) = "$USERS/${accountId.value}"

    fun userDocument(accountId: AccountId, collection: UserCollection, documentId: String): String {
        require(documentId.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        return "${userRoot(accountId)}/${collection.segment}/$documentId"
    }
}

object StorageContract {
    fun identificationOriginal(accountId: AccountId, requestId: String, fileName: String) =
        ownerPath("identification-originals", accountId, requestId, fileName)

    fun representativePhoto(accountId: AccountId, plantId: String, fileName: String) =
        ownerPath("plant-photos", accountId, plantId, fileName)

    fun shareImage(accountId: AccountId, shareId: String, fileName: String) =
        ownerPath("share-images", accountId, shareId, fileName)

    private fun ownerPath(
        prefix: String,
        accountId: AccountId,
        aggregateId: String,
        fileName: String,
    ): String {
        require(aggregateId.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        require(fileName.matches(Regex("^[A-Za-z0-9_.-]{1,160}$")) && !fileName.contains(".."))
        return "$prefix/${accountId.value}/$aggregateId/$fileName"
    }
}

interface RevisionedWriteDto {
    val ownerUid: String
    val revision: Long
    val expectedRevision: Long
    val idempotencyKey: String
    val updatedAt: String
}

data class UserAccountDto(
    override val ownerUid: String,
    val displayName: String?,
    val zoneId: String,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class PersonalPlantDto(
    override val ownerUid: String,
    val displayName: String,
    val contentId: String?,
    val registrationMethod: String,
    val representativePhotoPath: String?,
    val location: String?,
    val note: String?,
    val lastWateredDate: String?,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class WateringScheduleDto(
    val ownerUid: String,
    val plantId: String,
    val dueDate: String,
    val zoneId: String,
    val notificationCandidateActive: Boolean,
    val nextNotificationAt: Timestamp?,
    val revision: Long,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val updatedAt: Timestamp,
)

data class WateringRecordDto(
    override val ownerUid: String,
    val plantId: String,
    val wateredDate: String,
    val recordedAt: String,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class NotificationSettingDto(
    override val ownerUid: String,
    val wateringEnabled: Boolean,
    val weatherEnabled: Boolean,
    val defaultTime: String,
    val zoneId: String,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class WeatherSnapshotDto(
    override val ownerUid: String,
    val regionCode: String,
    val regionName: String,
    val temperatureCelsius: Double,
    val humidityPercent: Int,
    val precipitationMillimeters: Double,
    val observedAt: String,
    val expiresAt: String,
    val zoneId: String,
    val stale: Boolean,
    val unavailablePlantIds: List<String>,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class WeatherRiskDto(
    override val ownerUid: String,
    val plantId: String,
    val plantName: String,
    val snapshotId: String,
    val type: String,
    val reason: String,
    val action: String?,
    val detectedAt: String,
    val observedAt: String,
    val active: Boolean,
    val transition: Int,
    val deliveredTransition: Int?,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class MiniHomeDto(
    override val ownerUid: String,
    val name: String,
    val placedPlantCount: Int,
    val placementCount: Int,
    val placementIds: List<String>,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class PlacementDto(
    override val ownerUid: String,
    val miniHomeId: String,
    val layoutRevision: Long,
    val plantId: String?,
    val itemId: String?,
    val normalizedX: Double,
    val normalizedY: Double,
    val zIndex: Int,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class OwnedItemDto(
    override val ownerUid: String,
    val itemId: String,
    val acquiredAt: String,
    val applied: Boolean,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class ShareLinkDto(
    override val ownerUid: String,
    val miniHomeId: String,
    val sourceRevision: Long,
    val snapshotPath: String,
    val createdAt: String,
    val expiresAt: String,
    val revokedAt: String?,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class ConsentDto(
    override val ownerUid: String,
    val type: String,
    val granted: Boolean,
    val recordedAt: String,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class DeletionRequestDto(
    override val ownerUid: String,
    val requestedAt: String,
    val scheduledFor: String,
    val status: String,
    val completedAt: String?,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

enum class NotificationDeliveryStatus {
    SENT
}

data class NotificationDeliveryDto(
    val ownerUid: String,
    val plantId: String,
    val dueDate: String,
    val attempt: Int,
    val scheduledFor: Timestamp,
    val deliveredAt: Timestamp,
    val status: NotificationDeliveryStatus,
    val deduplicationKey: String,
    val revision: Long,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val updatedAt: Timestamp,
)

enum class NotificationHistoryStatus {
    SENT,
    FAILED,
    DELIVERED_AMBIGUOUS,
}

data class NotificationHistoryDto(
    val ownerUid: String,
    val plantId: String,
    val dueDate: String,
    val attempt: Int,
    val status: NotificationHistoryStatus,
    val deliveryConfirmedAt: Timestamp?,
    val failedAt: Timestamp?,
    val ambiguousAt: Timestamp?,
    val destinationOpened: Boolean,
    val openedAt: Timestamp?,
    val failureKind: String?,
    val deduplicationKey: String,
    val revision: Long,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val updatedAt: Timestamp,
) {
    init {
        when (status) {
            NotificationHistoryStatus.SENT ->
                require(deliveryConfirmedAt != null || openedAt != null)
            NotificationHistoryStatus.FAILED -> require(failedAt != null)
            NotificationHistoryStatus.DELIVERED_AMBIGUOUS ->
                require(deliveryConfirmedAt != null && ambiguousAt != null)
        }
        if (destinationOpened) {
            require(status == NotificationHistoryStatus.SENT && openedAt != null)
        }
    }
}

data class NotificationPlantSettingDto(
    val ownerUid: String,
    val plantId: String,
    val enabled: Boolean,
    val timeOverride: String?,
    val revision: Long,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val updatedAt: Timestamp,
)

data class PlantContentDto(
    val name: String,
    val wateringIntervalDays: Int?,
    val lightGuidance: String,
    val minimumTemperatureCelsius: Double?,
    val maximumTemperatureCelsius: Double?,
    val minimumHumidityPercent: Int?,
    val maximumHumidityPercent: Int?,
    val publicationState: String,
    val revision: Long,
    val updatedAt: String,
)

data class RiskContentDto(
    val type: String,
    val action: String,
    val publicationState: String,
    val revision: Long,
    val updatedAt: String,
)

data class ShopItemDto(
    val name: String,
    val description: String,
    val category: String,
    val assetPath: String,
    val assetSha256: String,
    val assetByteSize: Long,
    val assetContentType: String,
    val assetWidth: Int,
    val assetHeight: Int,
    val assetMediaRevision: Long,
    val acquisitionCondition: String?,
    val publicationState: String,
    val revision: Long,
    val updatedAt: String,
)

data class IdentificationRequestDto(
    override val ownerUid: String,
    val mediaReference: PrivateMediaReference,
    val createdAt: Timestamp,
    val expiresAt: Timestamp,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class PublicShareSnapshotDto(
    val publicationState: String,
    val sourceRevision: Long,
    val snapshotPath: String,
    val expiresAt: Timestamp,
    val revokedAt: Timestamp?,
)

object FirestoreTimestampAdapter {
    fun fromInstant(value: Instant): Timestamp = Timestamp(value.epochSecond, value.nano)

    fun toInstant(value: Timestamp): Instant =
        Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
}

data class ContentAuditDto(
    val contentId: String,
    val actorId: String,
    val action: String,
    val changedAt: String,
)

data class OperationReceiptDto(
    override val ownerUid: String,
    override val revision: Long,
    override val expectedRevision: Long,
    override val idempotencyKey: String,
    override val updatedAt: String,
) : RevisionedWriteDto

data class RemoteMutationCommand(
    val accountId: AccountId,
    val operationId: OperationId,
    val aggregateType: String,
    val aggregateId: String,
    val mutationType: String,
    val expectedRevision: Revision,
    val draftPayload: String,
)

sealed interface RemoteMutationResult {
    data class Applied(val revision: Long) : RemoteMutationResult

    data class Duplicate(val revision: Long) : RemoteMutationResult

    data class Conflict(val actualRevision: Long) : RemoteMutationResult

    data class Failed(val code: String) : RemoteMutationResult
}

fun interface RemoteMutationGateway {
    suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult
}
