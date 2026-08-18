package com.planterior.helper.feature.collection

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
import com.planterior.helper.core.model.PublicationState
import com.planterior.helper.core.model.RegistrationMethod
import com.planterior.helper.core.model.Revision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FirebaseCollectionRepository(
    private val database: PlanteriorDatabase,
    private val remote: CollectionRemoteDataSource,
    private val gateway: RemoteMutationGateway,
    private val now: () -> Instant = Instant::now,
) : CollectionRepository {
    override suspend fun loadCollection(): CollectionLoad {
        val account = activeAccountOrNull() ?: return CollectionLoad.Failed
        val cached = database.cacheDao().plants(account.value)
        if (!isActive(account)) return CollectionLoad.Failed
        return try {
            val fresh = remote.plants(account)
            if (!isActive(account) || fresh.any { it.accountId != account }) {
                return CollectionLoad.Failed
            }
            val ordered =
                fresh.sortedWith(
                    compareByDescending<RemotePersonalPlant> { it.updatedAt }.thenBy { it.id.value }
                )
            if (!isActive(account)) return CollectionLoad.Failed
            database
                .cacheDao()
                .reconcilePlants(account.value, ordered.map(RemotePersonalPlant::cache))
            if (!isActive(account)) return CollectionLoad.Failed
            CollectionLoad.Fresh(ordered.map(RemotePersonalPlant::listItem))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!isActive(account) || cached.isEmpty()) return CollectionLoad.Failed
            val last = database.syncDao().lastSync(account.value, "PLANTS")
            if (!isActive(account)) return CollectionLoad.Failed
            CollectionLoad.Stale(
                cached.map(CachedPlantEntity::listItem),
                last?.syncedAtEpochMillis?.let(Instant::ofEpochMilli),
            )
        }
    }

    override suspend fun loadDetail(plantId: PersonalPlantId): DetailLoad {
        val account = activeAccountOrNull() ?: return DetailLoad.Forbidden
        val cached = database.cacheDao().plantBlocking(account.value, plantId.value)
        if (!isActive(account)) return DetailLoad.Forbidden
        val accountZone =
            try {
                remote.accountZone(account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        if (!isActive(account)) return DetailLoad.Forbidden
        if (accountZone == null) {
            return cached?.let {
                DetailLoad.Stale(
                    it.detail(account),
                    guidance = null,
                    editingAllowed = false,
                    accountZone = null,
                )
            } ?: DetailLoad.Failed
        }
        val lookup =
            try {
                remote.plant(account, plantId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RemotePlantLookup.Failed
            }
        if (!isActive(account)) return DetailLoad.Forbidden
        val remotePlant =
            when (lookup) {
                is RemotePlantLookup.Found -> lookup.plant
                RemotePlantLookup.Forbidden -> return DetailLoad.Forbidden
                RemotePlantLookup.NotFound -> return DetailLoad.NotFound
                RemotePlantLookup.Failed ->
                    return cached?.let {
                        DetailLoad.Stale(
                            it.detail(account),
                            guidance = null,
                            editingAllowed = it.detailsComplete,
                            accountZone = accountZone,
                        )
                    } ?: DetailLoad.Failed
            }
        if (remotePlant.accountId != account || !isActive(account)) return DetailLoad.Forbidden
        val plant = remotePlant.detail()
        val contentId = plant.contentId
        if (contentId == null) {
            return cacheDetail(
                account,
                remotePlant,
                DetailLoad.NoStandardContent(plant, accountZone),
            )
        }
        val content =
            try {
                remote.publicContent(contentId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!isActive(account)) return DetailLoad.Forbidden
                val detail = PlantDetail(plant, accountZone, emptyGuidance())
                return cacheDetail(
                    account,
                    remotePlant,
                    DetailLoad.Partial(detail, CareField.entries.toSet()),
                )
            }
        if (!isActive(account)) return DetailLoad.Forbidden
        if (content == null || content.publicationState != PublicationState.PUBLIC) {
            return cacheDetail(
                account,
                remotePlant,
                DetailLoad.NoStandardContent(plant, accountZone),
            )
        }
        var missing = content.missingFields().toMutableSet()
        val symptoms =
            try {
                remote
                    .publicSymptoms(contentId)
                    .also { if (!isActive(account)) return DetailLoad.Forbidden }
                    .filter { it.publicationState == PublicationState.PUBLIC }
                    .map(RemoteSymptomGuidance::public)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!isActive(account)) return DetailLoad.Forbidden
                missing += CareField.SYMPTOMS
                emptyList()
            }
        if (!isActive(account)) return DetailLoad.Forbidden
        val detail = PlantDetail(plant, accountZone, content.guidance(symptoms))
        val result =
            if (missing.isEmpty()) DetailLoad.Fresh(detail) else DetailLoad.Partial(detail, missing)
        return cacheDetail(account, remotePlant, result)
    }

    private suspend fun cacheDetail(
        account: AccountId,
        plant: RemotePersonalPlant,
        result: DetailLoad,
    ): DetailLoad {
        if (!isActive(account)) return DetailLoad.Forbidden
        database.cacheDao().upsertPlant(plant.cache())
        return if (isActive(account)) result else DetailLoad.Forbidden
    }

    override suspend fun saveEdit(request: PlantEditRequest): EditResult {
        val active = activeAccountOrNull() ?: return EditResult.Forbidden
        if (active != request.accountId) return EditResult.Forbidden
        val payload = request.payload()
        val outbox =
            OperationOutboxEntity(
                operationId = request.operationId.value,
                accountId = request.accountId.value,
                aggregateType = "personalPlants",
                aggregateId = request.plantId.value,
                mutationType = "UPDATE",
                expectedRevision = request.expectedRevision,
                draftPayload = payload,
                createdAtEpochMillis = now().toEpochMilli(),
            )
        try {
            val existing =
                database.syncDao().operation(request.accountId.value, request.operationId.value)
            if (!isActive(request.accountId)) return EditResult.Forbidden
            if (existing != null && !existing.matchesFrozen(outbox)) {
                database
                    .syncDao()
                    .markFailed(
                        request.accountId.value,
                        request.operationId.value,
                        "OUTBOX_MISMATCH",
                    )
                if (!isActive(request.accountId)) return EditResult.Forbidden
                return EditResult.Failed(EditFailure.OUTBOX_MISMATCH)
            }
            database.syncDao().enqueue(outbox)
            if (!isActive(request.accountId)) return EditResult.Forbidden
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return EditResult.Failed(EditFailure.DATABASE_UNAVAILABLE)
        }
        if (activeAccountOrNull() != request.accountId) return EditResult.Forbidden
        val result =
            try {
                gateway.apply(
                    RemoteMutationCommand(
                        request.accountId,
                        request.operationId,
                        "personalPlants",
                        request.plantId.value,
                        "UPDATE",
                        Revision(request.expectedRevision),
                        payload,
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RemoteMutationResult.Failed("UNAVAILABLE")
            }
        val revision =
            when (result) {
                is RemoteMutationResult.Applied -> result.revision
                is RemoteMutationResult.Duplicate -> result.revision
                is RemoteMutationResult.Conflict -> {
                    return markConflict(request, result.actualRevision)
                }
                is RemoteMutationResult.Failed -> return markFailed(request, result.code)
            }
        if (activeAccountOrNull() != request.accountId) return EditResult.Forbidden
        val committed =
            try {
                remote.plant(request.accountId, request.plantId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RemotePlantLookup.Failed
            }
        if (!isActive(request.accountId)) return EditResult.Forbidden
        val plant = (committed as? RemotePlantLookup.Found)?.plant
        if (plant == null || !plant.matches(request, revision)) {
            return EditResult.Failed(EditFailure.INCONSISTENT_RECEIPT)
        }
        return try {
            if (!isActive(request.accountId)) return EditResult.Forbidden
            database.cacheDao().upsertPlant(plant.cache())
            if (!isActive(request.accountId)) return EditResult.Forbidden
            database.syncDao().remove(request.accountId.value, request.operationId.value)
            if (!isActive(request.accountId)) return EditResult.Forbidden
            EditResult.Saved(plant.detail())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            EditResult.Failed(EditFailure.DATABASE_UNAVAILABLE)
        }
    }

    override suspend fun reconcileFailedEdit(
        accountId: AccountId,
        plantId: PersonalPlantId,
        operationId: OperationId,
    ): DetailLoad {
        if (activeAccountOrNull() != accountId) return DetailLoad.Forbidden
        return try {
            val frozen = database.syncDao().operation(accountId.value, operationId.value)
            if (
                frozen != null &&
                    (frozen.aggregateType != "personalPlants" ||
                        frozen.aggregateId != plantId.value)
            ) {
                return DetailLoad.Failed
            }
            if (!isActive(accountId)) return DetailLoad.Forbidden
            if (frozen?.state == "PENDING") {
                database.syncDao().markFailed(accountId.value, operationId.value, "OUTBOX_MISMATCH")
                if (!isActive(accountId)) return DetailLoad.Forbidden
            }
            loadDetail(plantId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DetailLoad.Failed
        }
    }

    private fun isActive(accountId: AccountId): Boolean = activeAccountOrNull() == accountId

    private fun activeAccountOrNull(): AccountId? =
        try {
            remote.activeAccount()
        } catch (_: Exception) {
            null
        }

    private suspend fun markConflict(
        request: PlantEditRequest,
        actualRevision: Long,
    ): EditResult =
        try {
            database
                .syncDao()
                .markConflict(
                    request.accountId.value,
                    request.operationId.value,
                    actualRevision,
                )
            EditResult.Failed(EditFailure.REVISION_CONFLICT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            EditResult.Failed(EditFailure.DATABASE_UNAVAILABLE)
        }

    private suspend fun markFailed(request: PlantEditRequest, code: String): EditResult =
        try {
            database.syncDao().markFailed(request.accountId.value, request.operationId.value, code)
            EditResult.Failed(EditFailure.REMOTE_WRITE_FAILED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            EditResult.Failed(EditFailure.DATABASE_UNAVAILABLE)
        }
}

class FirebaseCollectionRemoteDataSource(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : CollectionRemoteDataSource {
    override fun activeAccount(): AccountId =
        auth.currentUser?.uid?.let(::AccountId) ?: error("Authentication is required")

    override suspend fun accountZone(accountId: AccountId): ZoneId {
        require(activeAccount() == accountId)
        val snapshot = firestore.document("users/${accountId.value}").get().await()
        require(activeAccount() == accountId)
        return ZoneId.of(requireNotNull(snapshot.getString("zoneId")))
    }

    override suspend fun plants(accountId: AccountId): List<RemotePersonalPlant> =
        firestore
            .collection("users/${accountId.value}/personalPlants")
            .get()
            .await()
            .documents
            .map { it.remotePlant(accountId) }

    override suspend fun plant(
        accountId: AccountId,
        plantId: PersonalPlantId,
    ): RemotePlantLookup =
        try {
            val document =
                firestore
                    .document("users/${accountId.value}/personalPlants/${plantId.value}")
                    .get()
                    .await()
            if (!document.exists()) RemotePlantLookup.NotFound
            else RemotePlantLookup.Found(document.remotePlant(accountId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFirestoreException) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                RemotePlantLookup.Forbidden
            } else {
                RemotePlantLookup.Failed
            }
        } catch (_: Exception) {
            RemotePlantLookup.Failed
        }

    override suspend fun publicContent(contentId: PlantContentId): RemotePlantContent? =
        firestore
            .collection("plantContents")
            .whereEqualTo("publicationState", PublicationState.PUBLIC.name)
            .whereEqualTo(FieldPath.documentId(), contentId.value)
            .limit(1)
            .get()
            .await()
            .documents
            .singleOrNull()
            ?.let { document ->
                RemotePlantContent(
                    id = PlantContentId(document.id),
                    wateringIntervalDays =
                        document.getLong("wateringIntervalDays").toPublicWateringIntervalDays(),
                    lightGuidance = document.getString("lightGuidance")?.takeIf(String::isNotBlank),
                    minimumTemperatureCelsius = document.getDouble("minimumTemperatureCelsius"),
                    maximumTemperatureCelsius = document.getDouble("maximumTemperatureCelsius"),
                    minimumHumidityPercent = document.getLong("minimumHumidityPercent")?.toInt(),
                    maximumHumidityPercent = document.getLong("maximumHumidityPercent")?.toInt(),
                    publicationState =
                        PublicationState.valueOf(
                            requireNotNull(document.getString("publicationState"))
                        ),
                )
            }

    override suspend fun publicSymptoms(contentId: PlantContentId): List<RemoteSymptomGuidance> =
        firestore
            .collection("riskContents")
            .whereEqualTo("publicationState", PublicationState.PUBLIC.name)
            .whereEqualTo("plantContentId", contentId.value)
            .get()
            .await()
            .documents
            .map { document ->
                RemoteSymptomGuidance(
                    id = document.id,
                    symptom = requireNotNull(document.getString("symptom")),
                    possibleCause = requireNotNull(document.getString("possibleCause")),
                    action = requireNotNull(document.getString("action")),
                    publicationState =
                        PublicationState.valueOf(
                            requireNotNull(document.getString("publicationState"))
                        ),
                )
            }
}

private fun DocumentSnapshot.remotePlant(expectedAccount: AccountId): RemotePersonalPlant {
    val owner = AccountId(requireNotNull(getString("ownerUid")))
    check(owner == expectedAccount) { "Owner scope mismatch" }
    return RemotePersonalPlant(
        accountId = owner,
        id = PersonalPlantId(id),
        displayName = requireNotNull(getString("displayName")),
        contentId = getString("contentId")?.let(::PlantContentId),
        registrationMethod =
            RegistrationMethod.valueOf(requireNotNull(getString("registrationMethod"))),
        representativePhotoPath = getString("representativePhotoPath"),
        location = getString("location"),
        privateNote = getString("note"),
        lastWateredDate = getString("lastWateredDate")?.let(LocalDate::parse),
        revision = requireNotNull(getLong("revision")),
        updatedAt = requireNotNull(getTimestamp("updatedAt")).toDate().toInstant(),
    )
}

private fun RemotePersonalPlant.cache() =
    CachedPlantEntity(
        accountId = accountId.value,
        plantId = id.value,
        displayName = displayName,
        representativePhotoPath = representativePhotoPath,
        revision = revision,
        updatedAtEpochMillis = updatedAt.toEpochMilli(),
        contentId = contentId?.value,
        registrationMethod = registrationMethod.name,
        location = location,
        note = privateNote,
        lastWateredDate = lastWateredDate?.toString(),
        detailsComplete = true,
    )

private fun RemotePersonalPlant.listItem() =
    CollectionPlant(id, displayName, representativePhotoPath)

private fun CachedPlantEntity.listItem() =
    CollectionPlant(PersonalPlantId(plantId), displayName, representativePhotoPath)

private fun RemotePersonalPlant.detail() =
    PersonalPlantDetail(
        accountId = accountId,
        id = id,
        displayName = displayName,
        contentId = contentId,
        registrationMethod = registrationMethod,
        representativePhotoPath = representativePhotoPath,
        location = location,
        privateNote = privateNote,
        lastWateredDate = lastWateredDate,
        revision = revision,
        updatedAt = updatedAt,
    )

private fun CachedPlantEntity.detail(account: AccountId) =
    PersonalPlantDetail(
        accountId = account,
        id = PersonalPlantId(plantId),
        displayName = displayName,
        contentId = contentId?.let(::PlantContentId),
        registrationMethod = RegistrationMethod.valueOf(registrationMethod),
        representativePhotoPath = representativePhotoPath,
        location = location,
        privateNote = note,
        lastWateredDate = lastWateredDate?.let(LocalDate::parse),
        revision = revision,
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

internal fun Long?.toPublicWateringIntervalDays(): Int? = this?.takeIf { it in 1L..365L }?.toInt()

private fun RemotePlantContent.missingFields(): Set<CareField> = buildSet {
    if (wateringIntervalDays == null) add(CareField.WATER)
    if (lightGuidance.isNullOrBlank()) add(CareField.LIGHT)
    if (minimumTemperatureCelsius == null || maximumTemperatureCelsius == null) {
        add(CareField.TEMPERATURE)
    }
    if (minimumHumidityPercent == null || maximumHumidityPercent == null) {
        add(CareField.HUMIDITY)
    }
}

private fun RemotePlantContent.guidance(symptoms: List<PublicSymptomGuidance>) =
    PlantCareGuidance(
        wateringIntervalDays,
        lightGuidance,
        minimumTemperatureCelsius,
        maximumTemperatureCelsius,
        minimumHumidityPercent,
        maximumHumidityPercent,
        symptoms,
    )

private fun emptyGuidance(): PlantCareGuidance =
    PlantCareGuidance(null, null, null, null, null, null, emptyList())

private fun RemoteSymptomGuidance.public() =
    PublicSymptomGuidance(id, symptom, possibleCause, action)

private fun PlantEditRequest.payload(): String =
    JsonObject(
            buildMap {
                if (PlantEditField.LOCATION in dirtyFields) {
                    put("location", location?.let(::JsonPrimitive) ?: JsonNull)
                }
                if (PlantEditField.NOTE in dirtyFields) {
                    put("note", privateNote?.let(::JsonPrimitive) ?: JsonNull)
                }
                if (PlantEditField.LAST_WATERED_DATE in dirtyFields) {
                    put(
                        "lastWateredDate",
                        lastWateredDate?.toString()?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            }
        )
        .toString()

private fun OperationOutboxEntity.matchesFrozen(other: OperationOutboxEntity): Boolean =
    accountId == other.accountId &&
        operationId == other.operationId &&
        aggregateType == other.aggregateType &&
        aggregateId == other.aggregateId &&
        mutationType == other.mutationType &&
        expectedRevision == other.expectedRevision &&
        draftPayload == other.draftPayload

private fun RemotePersonalPlant.matches(
    request: PlantEditRequest,
    committedRevision: Long,
): Boolean =
    accountId == request.accountId &&
        id == request.plantId &&
        displayName == request.displayName &&
        contentId == request.contentId &&
        registrationMethod == request.registrationMethod &&
        representativePhotoPath == request.representativePhotoPath &&
        location == request.location &&
        privateNote == request.privateNote &&
        lastWateredDate == request.lastWateredDate &&
        revision == committedRevision

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}
