package com.planterior.helper.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val opaqueIdPattern = Regex("^[A-Za-z0-9_-]{1,128}$")

private fun requireOpaqueId(value: String): String {
    require(opaqueIdPattern.matches(value)) { "ID must be opaque and path-safe" }
    return value
}

@JvmInline
value class AccountId(val value: String) {
    init {
        requireOpaqueId(value)
    }

    companion object {
        val LEGACY = AccountId("legacy")
    }
}

@JvmInline
value class PersonalPlantId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class PlantContentId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class WateringScheduleId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class WateringRecordId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class WeatherSnapshotId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class WeatherRiskId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class MiniHomeId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class PlacementId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class ItemId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class ShareLinkId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class ConsentId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class DeletionRequestId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class NotificationDeliveryId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class IdentificationRequestId(val value: String) {
    init {
        requireOpaqueId(value)
    }
}

@JvmInline
value class Revision(val value: Long) {
    init {
        require(value >= 0) { "Revision cannot be negative" }
    }

    fun next() = Revision(Math.addExact(value, 1))
}

@JvmInline
value class NormalizedCoordinate(val value: Double) {
    init {
        require(value.isFinite() && value in 0.0..1.0) {
            "Normalized coordinate must be finite and inside the canvas"
        }
    }
}

@JvmInline
value class PlacementLayer(val value: Int) {
    init {
        require(value >= 0) { "Placement layer cannot be negative" }
    }
}

@JvmInline
value class OperationId(val value: String) {
    init {
        require(Regex("^[A-Za-z0-9_-]{8,128}$").matches(value)) {
            "Operation ID must be stable and path-safe"
        }
    }

    companion object {
        fun random(): OperationId = OperationId(java.util.UUID.randomUUID().toString())

        fun stable(
            accountId: AccountId,
            aggregateId: String,
            action: String,
            nonce: String,
        ): OperationId {
            requireOpaqueId(aggregateId)
            requireOpaqueId(action)
            require(nonce.isNotBlank())
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "${accountId.value}|$aggregateId|$action|$nonce"
                            .toByteArray(StandardCharsets.UTF_8)
                    )
            return OperationId(digest.joinToString("") { "%02x".format(it) })
        }
    }
}

enum class PublicationState {
    DRAFT,
    PUBLIC,
    PRIVATE,
}

enum class RegistrationMethod {
    IDENTIFIED,
    IDENTIFICATION_EDITED,
    MANUAL,
}

enum class RiskType {
    HIGH_TEMPERATURE,
    LOW_TEMPERATURE,
    DRY,
    OVERWATERED,
}

enum class ItemCategory {
    BACKGROUND,
    FURNITURE,
    DECORATION,
}

enum class DeletionStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    FAILED,
    PARTIALLY_FAILED,
    CANCELLED,
}

enum class DeliveryStatus {
    PENDING,
    SENT,
    OPENED,
    FAILED,
}

enum class ConsentType {
    IDENTIFICATION_PHOTO_PROCESSING,
    LOCATION,
    ANALYTICS,
}

data class UserAccount(
    val id: AccountId,
    val displayName: String?,
    val zoneId: ZoneId,
    val createdAt: Instant,
    val revision: Revision,
)

data class PersonalPlant(
    val id: PersonalPlantId,
    val displayName: String,
    val contentId: PlantContentId?,
    val registrationMethod: RegistrationMethod,
    val representativePhotoPath: String?,
    val location: String?,
    val note: String?,
    val lastWateredDate: LocalDate?,
    val revision: Revision,
    val updatedAt: Instant,
)

data class PlantContent(
    val id: PlantContentId,
    val name: String,
    val wateringIntervalDays: Int?,
    val lightGuidance: String,
    val minimumTemperatureCelsius: Double?,
    val maximumTemperatureCelsius: Double?,
    val minimumHumidityPercent: Int?,
    val maximumHumidityPercent: Int?,
    val symptoms: List<SymptomGuidance>,
    val publicationState: PublicationState,
    val revision: Revision,
    val updatedAt: Instant,
)

data class SymptomGuidance(
    val id: String,
    val symptom: String,
    val possibleCause: String,
    val action: String,
)

data class WateringSchedule(
    val id: WateringScheduleId,
    val plantId: PersonalPlantId,
    val dueDate: LocalDate,
    val reminderTime: LocalTime,
    val zoneId: ZoneId,
    val enabled: Boolean,
    val revision: Revision,
    val updatedAt: Instant,
)

data class WateringRecord(
    val id: WateringRecordId,
    val plantId: PersonalPlantId,
    val wateredDate: LocalDate,
    val recordedAt: Instant,
    val operationId: OperationId,
    val revision: Revision,
)

data class NotificationSetting(
    val wateringEnabled: Boolean,
    val weatherEnabled: Boolean,
    val defaultTime: LocalTime,
    val zoneId: ZoneId,
    val revision: Revision,
)

data class WeatherSnapshot(
    val id: WeatherSnapshotId,
    val regionCode: String,
    val temperatureCelsius: Double,
    val humidityPercent: Int,
    val precipitationMillimeters: Double,
    val observedAt: Instant,
    val expiresAt: Instant,
)

data class WeatherRisk(
    val id: WeatherRiskId,
    val plantId: PersonalPlantId,
    val snapshotId: WeatherSnapshotId,
    val type: RiskType,
    val action: String?,
    val detectedAt: Instant,
    val active: Boolean,
    val revision: Revision,
)

data class MiniHome(
    val id: MiniHomeId,
    val name: String,
    val placements: List<MiniHomePlacement>,
    val revision: Revision,
    val updatedAt: Instant,
)

data class MiniHomePlacement(
    val id: PlacementId,
    val plantId: PersonalPlantId?,
    val itemId: ItemId?,
    val normalizedX: NormalizedCoordinate,
    val normalizedY: NormalizedCoordinate,
    val zIndex: PlacementLayer,
)

data class RiskGuidanceContent(
    val id: String,
    val type: RiskType,
    val action: String,
    val publicationState: PublicationState,
    val revision: Revision,
    val updatedAt: Instant,
)

data class CatalogMediaIdentity(
    val path: String,
    val sha256: String,
    val byteSize: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val mediaRevision: Revision,
) {
    init {
        require(sha256.matches(Regex("^[a-f0-9]{64}$")))
        val match =
            requireNotNull(
                Regex("^catalog-assets/[A-Za-z0-9_-]{1,128}/([a-f0-9]{64})\\.(png|jpg|jpeg|webp)$")
                    .matchEntire(path)
            ) {
                "Catalog media path must contain its lowercase SHA-256 identity"
            }
        require(match.groupValues[1] == sha256) { "Catalog media path digest differs" }
        require(byteSize in 1..8L * 1024L * 1024L)
        require(width in 1..32_768 && height in 1..32_768)
        val pixels = width.toLong() * height
        require(pixels <= 64L * 1024L * 1024L && pixels * 4L <= 256L * 1024L * 1024L)
        require(maxOf(width, height).toLong() <= minOf(width, height).toLong() * 32L)
        val extension = match.groupValues[2]
        require(
            (extension == "png" && mimeType == "image/png") ||
                (extension in setOf("jpg", "jpeg") && mimeType == "image/jpeg") ||
                (extension == "webp" && mimeType == "image/webp")
        )
        require(mediaRevision.value >= 1)
    }

    val cacheKey: String
        get() = "$path#$sha256@${mediaRevision.value}"
}

data class ShopItem(
    val id: ItemId,
    val name: String,
    val description: String,
    val category: ItemCategory,
    val mediaIdentity: CatalogMediaIdentity,
    val acquisitionCondition: String?,
    val publicationState: PublicationState,
    val revision: Revision,
) {
    val assetPath: String
        get() = mediaIdentity.path
}

data class OwnedItem(
    val itemId: ItemId,
    val acquiredAt: Instant,
    val applied: Boolean,
    val revision: Revision,
)

data class ShareLink(
    val id: ShareLinkId,
    val miniHomeId: MiniHomeId,
    val sourceRevision: Revision,
    val snapshotPath: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class ConsentRecord(
    val id: ConsentId,
    val type: ConsentType,
    val granted: Boolean,
    val recordedAt: Instant,
    val revision: Revision,
)

data class AccountDeletionRequest(
    val id: DeletionRequestId,
    val requestedAt: Instant,
    val scheduledFor: Instant,
    val status: DeletionStatus,
    val completedAt: Instant?,
    val revision: Revision,
)

data class NotificationDelivery(
    val id: NotificationDeliveryId,
    val plantId: PersonalPlantId?,
    val scheduledFor: Instant,
    val deliveredAt: Instant?,
    val status: DeliveryStatus,
    val deduplicationKey: String,
    val revision: Revision,
)

data class ContentAudit(
    val id: String,
    val contentId: String,
    val actorId: String,
    val action: String,
    val changedAt: Instant,
)
