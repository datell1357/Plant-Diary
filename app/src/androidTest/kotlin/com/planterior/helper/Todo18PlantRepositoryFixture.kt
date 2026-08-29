package com.planterior.helper

import com.planterior.helper.core.data.PrivateMediaReference
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.PublicationState
import com.planterior.helper.feature.collection.CollectionRemoteDataSource
import com.planterior.helper.feature.collection.RemotePersonalPlant
import com.planterior.helper.feature.collection.RemotePlantContent
import com.planterior.helper.feature.collection.RemotePlantLookup
import com.planterior.helper.feature.collection.RemoteSymptomGuidance
import com.planterior.helper.feature.registration.ExistingPersonalPlant
import com.planterior.helper.feature.registration.PendingRegistration
import com.planterior.helper.feature.registration.RegistrationContent
import com.planterior.helper.feature.registration.RegistrationRemoteDataSource
import com.planterior.helper.feature.registration.RepresentativePhoto
import com.planterior.helper.feature.watering.WateringCompletionReceipt
import com.planterior.helper.feature.watering.WateringCompletionRequest
import com.planterior.helper.feature.watering.WateringConfirmActionDiagnostics
import com.planterior.helper.feature.watering.WateringConfirmActionObservation
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import com.planterior.helper.feature.watering.WateringReceiptLookup
import com.planterior.helper.feature.watering.WateringRemoteDataSource
import com.planterior.helper.feature.watering.WateringRequestHash
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Registration, collection, and watering remotes sharing one deterministic plant aggregate. */
internal class Todo18PlantRepositoryFixture(private val scenario: Todo18Scenario) :
    RegistrationRemoteDataSource,
    CollectionRemoteDataSource,
    WateringRemoteDataSource,
    RemoteMutationGateway {
    private val wateringReceipts = mutableMapOf<String, WateringCompletionReceipt>()

    override fun activeAccount(): AccountId = scenario.accountId

    override suspend fun accountZone(accountId: AccountId): ZoneId {
        require(accountId == scenario.accountId)
        return scenario.zone
    }

    override suspend fun search(query: String): List<RegistrationContent> {
        scenario.emit("registration-search", query)
        return if (query.contains("monstera", ignoreCase = true) || query.contains("몬스테라")) {
            listOf(RegistrationContent(scenario.contentId, "몬스테라"))
        } else {
            emptyList()
        }
    }

    override suspend fun duplicates(
        accountId: AccountId,
        contentId: String,
    ): List<ExistingPersonalPlant> = emptyList()

    override suspend fun uploadRepresentativePhoto(
        accountId: AccountId,
        plantId: PersonalPlantId,
        operationId: OperationId,
        photo: RepresentativePhoto,
    ): PrivateMediaReference = error("Todo18 registration does not choose a representative photo")

    override suspend fun readCommitted(
        submission: PendingRegistration,
        revision: Long,
        mediaReference: PrivateMediaReference?,
    ): PersonalPlant {
        val plant = submission.toPersonalPlant(revision, scenario.now())
        scenario.store(plant)
        scenario.emit("registration-committed", plant.id.value)
        return plant
    }

    override suspend fun plants(accountId: AccountId): List<RemotePersonalPlant> {
        check(scenario.collectionOnline)
        require(accountId == scenario.accountId)
        scenario.emit("collection-loaded", accountId.value)
        return scenario.plants.values.toList()
    }

    override suspend fun plant(
        accountId: AccountId,
        plantId: PersonalPlantId,
    ): RemotePlantLookup {
        require(accountId == scenario.accountId)
        return scenario.plants[plantId]?.let(RemotePlantLookup::Found) ?: RemotePlantLookup.NotFound
    }

    override suspend fun publicContent(contentId: PlantContentId): RemotePlantContent? =
        RemotePlantContent(
            id = contentId,
            wateringIntervalDays = 7,
            lightGuidance = "밝은 간접광",
            minimumTemperatureCelsius = 18.0,
            maximumTemperatureCelsius = 28.0,
            minimumHumidityPercent = 40,
            maximumHumidityPercent = 70,
            publicationState = PublicationState.PUBLIC,
        )

    override suspend fun publicSymptoms(contentId: PlantContentId): List<RemoteSymptomGuidance> =
        emptyList()

    override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
        if (scenario.mutationConflict) {
            return RemoteMutationResult.Conflict(command.expectedRevision.value + 1)
        }
        if (command.aggregateType != "wateringCompletions") {
            return RemoteMutationResult.Applied(command.expectedRevision.value + 1)
        }
        val plantId = PersonalPlantId(command.aggregateId)
        val current = requireNotNull(scenario.plants[plantId])
        val watered =
            LocalDate.parse(
                Json.parseToJsonElement(command.draftPayload)
                    .jsonObject
                    .getValue("wateredDate")
                    .jsonPrimitive
                    .content
            )
        val revision = command.expectedRevision.value + 1
        scenario.plants[plantId] =
            current.copy(
                lastWateredDate = watered,
                revision = revision,
                updatedAt = scenario.now(),
            )
        wateringReceipts[command.operationId.value] = receipt(command, plantId, watered, revision)
        scenario.emit("watering-applied", command.operationId.value)
        return RemoteMutationResult.Applied(revision)
    }

    override suspend fun receipt(
        accountId: AccountId,
        plantId: PersonalPlantId,
        operationId: OperationId,
    ): WateringReceiptLookup {
        require(accountId == scenario.accountId)
        val result = wateringReceipts[operationId.value]
        WateringConfirmActionDiagnostics.observe(
            WateringConfirmActionObservation(
                WateringConfirmActionStage.RECEIPT_LOOKUP_RESULT,
                plantId,
                operationId,
            )
        )
        if (result != null) {
            WateringConfirmActionDiagnostics.observe(
                WateringConfirmActionObservation(
                    WateringConfirmActionStage.FIXTURE_RECEIPT_EMIT,
                    plantId,
                    operationId,
                )
            )
            scenario.emit("watering-receipt", operationId.value)
        }
        return result?.let(WateringReceiptLookup::Found) ?: WateringReceiptLookup.NotFound
    }

    private fun receipt(
        command: RemoteMutationCommand,
        plantId: PersonalPlantId,
        watered: LocalDate,
        revision: Long,
    ): WateringCompletionReceipt {
        val request =
            WateringCompletionRequest(
                scenario.accountId,
                plantId,
                command.expectedRevision.value,
                command.operationId,
                watered,
                scenario.zone,
            )
        return WateringCompletionReceipt(
            accountId = scenario.accountId,
            plantId = plantId,
            operationId = command.operationId,
            recordId = command.operationId.value,
            wateredDate = watered,
            nextDueDate = watered.plusDays(7),
            plantRevision = revision,
            scheduleRevision = revision,
            recordedAt = scenario.now(),
            accountZone = scenario.zone,
            requestHash = WateringRequestHash.calculate(request),
        )
    }
}
