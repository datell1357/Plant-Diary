package com.planterior.helper.feature.registration

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlant
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlantContentId
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
import kotlinx.serialization.json.contentOrNull

fun interface PreparedPhotoBytesReader {
    fun read(photo: com.planterior.helper.feature.camera.PreparedPhoto): ByteArray
}

interface RegistrationRemoteDataSource {
    fun activeAccount(): AccountId

    suspend fun accountZone(accountId: AccountId): ZoneId

    suspend fun search(query: String): List<RegistrationContent>

    suspend fun duplicates(
        accountId: AccountId,
        contentId: String,
    ): List<ExistingPersonalPlant>

    suspend fun uploadRepresentativePhoto(
        accountId: AccountId,
        plantId: PersonalPlantId,
        photo: RepresentativePhoto,
    ): String

    suspend fun readCommitted(
        submission: PendingRegistration,
        revision: Long,
        photoPath: String?,
    ): PersonalPlant
}

class FirebaseRegistrationRepository(
    private val database: PlanteriorDatabase,
    private val remote: RegistrationRemoteDataSource,
    private val gateway: RemoteMutationGateway,
    private val now: () -> Instant = Instant::now,
) : RegistrationRepository {
    override suspend fun session(): RegistrationSession {
        val account = remote.activeAccount()
        return RegistrationSession(account, remote.accountZone(account))
    }

    override suspend fun searchPublicContents(query: String): List<RegistrationContent> =
        remote.search(query)

    override suspend fun findDuplicates(
        accountId: AccountId,
        contentId: PlantContentId,
        excluding: PersonalPlantId,
    ): List<ExistingPersonalPlant> =
        remote.duplicates(accountId, contentId.value).filterNot { it.id == excluding }

    override suspend fun reconcileCheckpoint(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ): RegistrationCheckpoint {
        val existing =
            database.syncDao().operation(submission.accountId.value, submission.operationId.value)
                ?: return checkpoint
        val payload =
            kotlinx.serialization.json.Json.parseToJsonElement(existing.draftPayload) as? JsonObject
                ?: return checkpoint
        val path = (payload["representativePhotoPath"] as? JsonPrimitive)?.contentOrNull
        return when {
            checkpoint is RegistrationCheckpoint.PlantCommitted -> checkpoint
            path != null -> RegistrationCheckpoint.PhotoStored(path)
            else -> checkpoint
        }
    }

    override suspend fun register(
        submission: PendingRegistration,
        checkpoint: RegistrationCheckpoint,
    ): RegistrationAttempt {
        val activeAccount =
            try {
                remote.activeAccount()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return RegistrationAttempt.Failed(RegistrationFailure.UNAUTHENTICATED, checkpoint)
            }
        if (activeAccount != submission.accountId) {
            return RegistrationAttempt.Failed(RegistrationFailure.UNAUTHENTICATED, checkpoint)
        }
        var current = checkpoint
        val photoPath =
            when (current) {
                RegistrationCheckpoint.NotStarted -> {
                    if (submission.photo == null) null
                    else {
                        try {
                                remote.uploadRepresentativePhoto(
                                    submission.accountId,
                                    submission.plantId,
                                    submission.photo,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                return RegistrationAttempt.Failed(
                                    RegistrationFailure.PHOTO_UPLOAD_FAILED,
                                    current,
                                )
                            }
                            .also { current = RegistrationCheckpoint.PhotoStored(it) }
                    }
                }
                is RegistrationCheckpoint.PhotoStored -> current.path
                is RegistrationCheckpoint.PlantCommitted -> current.photoPath
            }
        val committed = current as? RegistrationCheckpoint.PlantCommitted
        val revision =
            if (committed != null) committed.revision
            else {
                val stillActive =
                    try {
                        remote.activeAccount()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return RegistrationAttempt.Failed(
                            RegistrationFailure.UNAUTHENTICATED,
                            current,
                        )
                    }
                if (stillActive != submission.accountId) {
                    return RegistrationAttempt.Failed(RegistrationFailure.UNAUTHENTICATED, current)
                }
                val payload = submission.payload(photoPath)
                val outbox =
                    OperationOutboxEntity(
                        submission.operationId.value,
                        submission.accountId.value,
                        "personalPlants",
                        submission.plantId.value,
                        "CREATE",
                        0,
                        payload,
                        now().toEpochMilli(),
                    )
                try {
                    val existing =
                        database
                            .syncDao()
                            .operation(
                                submission.accountId.value,
                                submission.operationId.value,
                            )
                    if (existing != null && !existing.matchesFrozen(outbox)) {
                        return RegistrationAttempt.Failed(
                            RegistrationFailure.OUTBOX_MISMATCH,
                            current,
                        )
                    }
                    database.syncDao().enqueue(outbox)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return RegistrationAttempt.Failed(
                        RegistrationFailure.DATABASE_UNAVAILABLE,
                        current,
                    )
                }
                val result =
                    try {
                        gateway.apply(
                            RemoteMutationCommand(
                                submission.accountId,
                                submission.operationId,
                                "personalPlants",
                                submission.plantId.value,
                                "CREATE",
                                Revision(0),
                                payload,
                            )
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        RemoteMutationResult.Failed("UNAVAILABLE")
                    }
                when (result) {
                    is RemoteMutationResult.Applied -> result.revision
                    is RemoteMutationResult.Duplicate -> result.revision
                    is RemoteMutationResult.Conflict -> {
                        try {
                            database
                                .syncDao()
                                .markConflict(
                                    submission.accountId.value,
                                    submission.operationId.value,
                                    result.actualRevision,
                                )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            return RegistrationAttempt.Failed(
                                RegistrationFailure.DATABASE_UNAVAILABLE,
                                current,
                            )
                        }
                        return RegistrationAttempt.Failed(
                            RegistrationFailure.REVISION_CONFLICT,
                            current,
                        )
                    }
                    is RemoteMutationResult.Failed -> {
                        try {
                            database
                                .syncDao()
                                .markFailed(
                                    submission.accountId.value,
                                    submission.operationId.value,
                                    result.code,
                                )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            return RegistrationAttempt.Failed(
                                RegistrationFailure.DATABASE_UNAVAILABLE,
                                current,
                            )
                        }
                        return RegistrationAttempt.Failed(
                            RegistrationFailure.REMOTE_WRITE_FAILED,
                            current,
                        )
                    }
                }
            }
        val committedCheckpoint = RegistrationCheckpoint.PlantCommitted(revision, photoPath)
        val plant =
            try {
                remote.readCommitted(submission, revision, photoPath)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return RegistrationAttempt.Failed(
                    RegistrationFailure.INCONSISTENT_RECEIPT,
                    committedCheckpoint,
                )
            }
        try {
            database
                .cacheDao()
                .upsertPlant(
                    CachedPlantEntity(
                        accountId = submission.accountId.value,
                        plantId = plant.id.value,
                        displayName = plant.displayName,
                        representativePhotoPath = plant.representativePhotoPath,
                        revision = plant.revision.value,
                        updatedAtEpochMillis = plant.updatedAt.toEpochMilli(),
                        contentId = plant.contentId?.value,
                        registrationMethod = plant.registrationMethod.name,
                        location = plant.location,
                        note = plant.note,
                        lastWateredDate = plant.lastWateredDate?.toString(),
                        detailsComplete = true,
                    )
                )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegistrationAttempt.Failed(
                RegistrationFailure.CACHE_WRITE_FAILED,
                committedCheckpoint,
            )
        }
        try {
            database.syncDao().remove(submission.accountId.value, submission.operationId.value)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RegistrationAttempt.Failed(
                RegistrationFailure.DATABASE_UNAVAILABLE,
                committedCheckpoint,
            )
        }
        return RegistrationAttempt.Completed(plant)
    }
}

class FirebaseRegistrationRemoteDataSource(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val preparedPhotoBytes: PreparedPhotoBytesReader,
) : RegistrationRemoteDataSource {
    override fun activeAccount(): AccountId =
        auth.currentUser?.uid?.let(::AccountId) ?: error("Authentication is required")

    override suspend fun accountZone(accountId: AccountId): ZoneId {
        val snapshot = firestore.document("users/${accountId.value}").get().await()
        return ZoneId.of(requireNotNull(snapshot.getString("zoneId")))
    }

    override suspend fun search(query: String): List<RegistrationContent> =
        firestore
            .collection("plantContents")
            .whereEqualTo("publicationState", "PUBLIC")
            .orderBy("name")
            .startAt(query)
            .endAt("$query\uf8ff")
            .limit(20)
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                val name = document.getString("name")?.trim().orEmpty()
                runCatching {
                    RegistrationContent(PlantContentId(document.id), name)
                }
                    .getOrNull()
                    ?.takeIf { name.isNotEmpty() }
            }

    override suspend fun duplicates(
        accountId: AccountId,
        contentId: String,
    ): List<ExistingPersonalPlant> =
        firestore
            .collection("users/${accountId.value}/personalPlants")
            .whereEqualTo("contentId", contentId)
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                runCatching {
                    ExistingPersonalPlant(
                        PersonalPlantId(document.id),
                        requireNotNull(document.getString("displayName")),
                    )
                }
                    .getOrNull()
            }

    override suspend fun uploadRepresentativePhoto(
        accountId: AccountId,
        plantId: PersonalPlantId,
        photo: RepresentativePhoto,
    ): String {
        val (bytes, extension, contentType) =
            when (photo) {
                is RepresentativePhoto.Bytes ->
                    Triple(photo.bytes, photo.extension, photo.contentType)
                is RepresentativePhoto.Prepared ->
                    Triple(
                        preparedPhotoBytes.read(photo.photo),
                        photo.extension,
                        photo.contentType,
                    )
                is RepresentativePhoto.IdentificationOriginal -> {
                    val request =
                        firestore
                            .document(
                                "users/${accountId.value}/identificationRequests/${photo.requestId}"
                            )
                            .get()
                            .await()
                    val originalPath = requireNotNull(request.getString("temporaryOriginalPath"))
                    require(
                        originalPath.startsWith(
                            "identification-originals/${accountId.value}/${photo.requestId}/"
                        )
                    )
                    val reference = storage.reference.child(originalPath)
                    val originalContentType =
                        reference.metadata.await().contentType?.takeIf {
                            it in SUPPORTED_PHOTO_TYPES
                        } ?: error("Unsupported identification photo")
                    Triple(
                        reference.getBytes(MAX_PHOTO_BYTES).await(),
                        extensionFor(originalContentType),
                        originalContentType,
                    )
                }
            }
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_PHOTO_BYTES)
        val path = "plant-photos/${accountId.value}/${plantId.value}/representative.$extension"
        val metadata =
            StorageMetadata.Builder()
                .setContentType(contentType)
                .setCustomMetadata("ownerUid", accountId.value)
                .build()
        storage.reference.child(path).putBytes(bytes, metadata).await()
        return path
    }

    override suspend fun readCommitted(
        submission: PendingRegistration,
        revision: Long,
        photoPath: String?,
    ): PersonalPlant {
        val document =
            firestore
                .document(
                    "users/${submission.accountId.value}/personalPlants/${submission.plantId.value}"
                )
                .get()
                .await()
        check(document.exists())
        val displayName = requireNotNull(document.getString("displayName"))
        val contentId = document.getString("contentId")?.let(::PlantContentId)
        val method =
            RegistrationMethod.valueOf(requireNotNull(document.getString("registrationMethod")))
        val storedPhoto = document.getString("representativePhotoPath")
        check(document.getString("ownerUid") == submission.accountId.value)
        check(document.getString("idempotencyKey") == submission.operationId.value)
        check(document.getLong("expectedRevision") == 0L)
        check(displayName == submission.displayName)
        check(contentId == submission.contentId)
        check(method == submission.method)
        check(storedPhoto == photoPath)
        check(document.getString("location") == null)
        check(document.getString("note") == null)
        check(document.getString("lastWateredDate") == submission.lastWateredDate?.toString())
        val storedRevision = requireNotNull(document.getLong("revision"))
        check(storedRevision == revision)
        val updatedAt = requireNotNull(document.get("updatedAt") as? Timestamp).toDate().toInstant()
        return PersonalPlant(
            submission.plantId,
            displayName,
            contentId,
            method,
            storedPhoto,
            document.getString("location"),
            document.getString("note"),
            document.getString("lastWateredDate")?.let(LocalDate::parse),
            Revision(storedRevision),
            updatedAt,
        )
    }

    private fun extensionFor(contentType: String) =
        when (contentType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heif" -> "heif"
            "image/heic" -> "heic"
            else -> "jpg"
        }

    private companion object {
        const val MAX_PHOTO_BYTES = 20L * 1024L * 1024L
        val SUPPORTED_PHOTO_TYPES =
            setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
    }
}

private fun OperationOutboxEntity.matchesFrozen(other: OperationOutboxEntity): Boolean =
    accountId == other.accountId &&
        operationId == other.operationId &&
        aggregateType == other.aggregateType &&
        aggregateId == other.aggregateId &&
        mutationType == other.mutationType &&
        expectedRevision == other.expectedRevision &&
        draftPayload == other.draftPayload

private fun PendingRegistration.payload(photoPath: String?): String =
    JsonObject(
            linkedMapOf(
                "displayName" to JsonPrimitive(displayName),
                "contentId" to (contentId?.value?.let(::JsonPrimitive) ?: JsonNull),
                "registrationMethod" to JsonPrimitive(method.name),
                "representativePhotoPath" to (photoPath?.let(::JsonPrimitive) ?: JsonNull),
                "location" to JsonNull,
                "note" to JsonNull,
                "lastWateredDate" to
                    (lastWateredDate?.toString()?.let(::JsonPrimitive) ?: JsonNull),
            )
        )
        .toString()

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
