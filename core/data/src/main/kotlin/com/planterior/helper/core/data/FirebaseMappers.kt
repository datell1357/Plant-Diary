package com.planterior.helper.core.data

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.core.model.Revision
import com.planterior.helper.core.model.WateringSchedule
import com.planterior.helper.core.model.WateringScheduleId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

fun WateringScheduleDto.toDomain(id: WateringScheduleId) =
    WateringSchedule(
        id,
        PersonalPlantId(plantId),
        LocalDate.parse(dueDate),
        LocalTime.parse(reminderTime),
        ZoneId.of(zoneId),
        enabled,
        Revision(revision),
        Instant.parse(updatedAt),
    )

fun WateringSchedule.toDto(metadata: WriteMetadata) =
    WateringScheduleDto(
        metadata.accountId.value,
        plantId.value,
        dueDate.toString(),
        reminderTime.toString(),
        zoneId.id,
        enabled,
        revision.value,
        metadata.expectedRevision.value,
        metadata.operationId.value,
        metadata.updatedAt.toString(),
    )
