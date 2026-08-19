package com.planterior.helper.core.data

import com.google.firebase.Timestamp
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.core.model.Revision
import java.time.Instant
import java.time.LocalDate

data class WriteMetadata(
    val accountId: AccountId,
    val expectedRevision: Revision,
    val operationId: OperationId,
    val updatedAt: Instant,
)

fun PersonalPlantDto.toDomain(id: PersonalPlantId) =
    PersonalPlant(
        id,
        displayName,
        contentId?.let(::PlantContentId),
        RegistrationMethod.valueOf(registrationMethod),
        representativePhotoPath,
        location,
        note,
        lastWateredDate?.let(LocalDate::parse),
        Revision(revision),
        Instant.parse(updatedAt),
    )

fun PersonalPlant.toDto(metadata: WriteMetadata) =
    PersonalPlantDto(
        metadata.accountId.value,
        displayName,
        contentId?.value,
        registrationMethod.name,
        representativePhotoPath,
        location,
        note,
        lastWateredDate?.toString(),
        revision.value,
        metadata.expectedRevision.value,
        metadata.operationId.value,
        metadata.updatedAt.toString(),
    )

fun Map<String, Any?>.toWateringScheduleDto() =
    WateringScheduleDto(
        ownerUid = requiredString("ownerUid"),
        plantId = requiredString("plantId"),
        dueDate = requiredString("dueDate"),
        zoneId = requiredString("zoneId"),
        notificationCandidateActive = requiredBoolean("notificationCandidateActive"),
        nextNotificationAt = optionalTimestamp("nextNotificationAt"),
        revision = requiredLong("revision"),
        expectedRevision = requiredLong("expectedRevision"),
        idempotencyKey = requiredString("idempotencyKey"),
        updatedAt = requiredTimestamp("updatedAt"),
    )

fun Map<String, Any?>.toNotificationDeliveryDto() =
    NotificationDeliveryDto(
        ownerUid = requiredString("ownerUid"),
        plantId = requiredString("plantId"),
        dueDate = requiredString("dueDate"),
        attempt = requiredAttempt(),
        scheduledFor = requiredTimestamp("scheduledFor"),
        deliveredAt = requiredTimestamp("deliveredAt"),
        status = NotificationDeliveryStatus.valueOf(requiredString("status")),
        deduplicationKey = requiredString("deduplicationKey"),
        revision = requiredLong("revision"),
        expectedRevision = requiredLong("expectedRevision"),
        idempotencyKey = requiredString("idempotencyKey"),
        updatedAt = requiredTimestamp("updatedAt"),
    )

fun Map<String, Any?>.toNotificationHistoryDto() =
    NotificationHistoryDto(
        ownerUid = requiredString("ownerUid"),
        plantId = requiredString("plantId"),
        dueDate = requiredString("dueDate"),
        attempt = requiredAttempt(),
        status = NotificationHistoryStatus.valueOf(requiredString("status")),
        deliveryConfirmedAt = optionalTimestamp("deliveryConfirmedAt"),
        failedAt = optionalTimestamp("failedAt"),
        ambiguousAt = optionalTimestamp("ambiguousAt"),
        destinationOpened = requiredBoolean("destinationOpened"),
        openedAt = optionalTimestamp("openedAt"),
        failureKind = optionalString("failureKind"),
        deduplicationKey = requiredString("deduplicationKey"),
        revision = requiredLong("revision"),
        expectedRevision = requiredLong("expectedRevision"),
        idempotencyKey = requiredString("idempotencyKey"),
        updatedAt = requiredTimestamp("updatedAt"),
    )

private fun Map<String, Any?>.requiredString(field: String): String =
    requireNotNull(this[field] as? String) { "$field must be a string" }

private fun Map<String, Any?>.optionalString(field: String): String? {
    val value = this[field] ?: return null
    return requireNotNull(value as? String) { "$field must be a string or null" }
}

private fun Map<String, Any?>.requiredBoolean(field: String): Boolean =
    requireNotNull(this[field] as? Boolean) { "$field must be a boolean" }

private fun Map<String, Any?>.requiredTimestamp(field: String): Timestamp =
    requireNotNull(this[field] as? Timestamp) { "$field must be a Firestore Timestamp" }

private fun Map<String, Any?>.optionalTimestamp(field: String): Timestamp? {
    val value = this[field] ?: return null
    return requireNotNull(value as? Timestamp) { "$field must be a Firestore Timestamp or null" }
}

private fun Map<String, Any?>.requiredLong(field: String): Long {
    val value = requireNotNull(this[field] as? Number) { "$field must be an integer" }
    val result = value.toLong()
    require(value.toDouble() == result.toDouble()) { "$field must be an integer" }
    return result
}

private fun Map<String, Any?>.requiredAttempt(): Int {
    val attempt = requiredLong("attempt")
    require(attempt == 0L || attempt == 1L) { "attempt must be 0 or 1" }
    return attempt.toInt()
}
