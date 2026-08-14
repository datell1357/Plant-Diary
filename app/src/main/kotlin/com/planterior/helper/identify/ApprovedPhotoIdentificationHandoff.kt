package com.planterior.helper.identify

import com.planterior.helper.core.data.FirestoreTimestampAdapter
import com.planterior.helper.core.data.IdentificationRequestDto
import com.planterior.helper.core.data.StorageContract
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoSubmission
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface PhotoIdentificationHandoff {
    suspend fun prepare(submission: PhotoSubmission)
}

fun interface PrivatePhotoBytes {
    suspend fun read(uri: String): ByteArray
}

data class TemporaryIdentificationOriginal(
    val path: String,
    val ownerUid: String,
    val requestId: String,
    val contentType: String,
    val expiresAt: Instant,
    val bytes: ByteArray,
)

interface IdentificationHandoffBackend {
    fun currentOwner(): AccountId?

    suspend fun findRequest(owner: AccountId, requestId: String): IdentificationRequestDto?

    suspend fun upload(original: TemporaryIdentificationOriginal)

    suspend fun createRequest(
        owner: AccountId,
        requestId: String,
        request: IdentificationRequestDto,
    )
}

enum class IdentificationHandoffFailure {
    Unauthenticated,
    PermissionDenied,
    PhotoUnavailable,
    UploadFailed,
    RequestFailed,
}

class IdentificationHandoffException(val reason: IdentificationHandoffFailure) :
    Exception(reason.name)

class ApprovedPhotoIdentificationHandoff(
    private val backend: IdentificationHandoffBackend,
    private val photoBytes: PrivatePhotoBytes,
) : PhotoIdentificationHandoff {
    private val mutex = Mutex()
    private val completed = mutableSetOf<RequestKey>()

    override suspend fun prepare(submission: PhotoSubmission) {
        val owner =
            backend.currentOwner()
                ?: throw IdentificationHandoffException(
                    IdentificationHandoffFailure.Unauthenticated
                )
        val requestId = IdentificationRequestId(submission.requestId)
        val key = RequestKey(owner, requestId)
        mutex.withLock {
            if (key in completed) return
            val request = submission.request(owner, requestId)
            val existing = requestLookup(owner, requestId)
            if (existing != null) {
                existing.requireSameRequest(request)
                completed += key
                return
            }
            val bytes = readPhoto(submission)
            upload(
                submission.original(owner, requestId, request.expiresAt.toDate().toInstant(), bytes)
            )
            createRequest(owner, requestId, request)
            completed += key
        }
    }

    private suspend fun requestLookup(
        owner: AccountId,
        requestId: IdentificationRequestId,
    ): IdentificationRequestDto? =
        try {
            backend.findRequest(owner, requestId.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IdentificationHandoffException) {
            throw error
        } catch (_: Exception) {
            throw IdentificationHandoffException(IdentificationHandoffFailure.RequestFailed)
        }

    private suspend fun readPhoto(submission: PhotoSubmission): ByteArray =
        try {
            photoBytes.read(submission.photo.privateUri).also {
                if (it.isEmpty() || it.size.toLong() != submission.photo.byteSize) {
                    throw IdentificationHandoffException(
                        IdentificationHandoffFailure.PhotoUnavailable
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IdentificationHandoffException) {
            throw error
        } catch (_: Exception) {
            throw IdentificationHandoffException(IdentificationHandoffFailure.PhotoUnavailable)
        }

    private suspend fun upload(original: TemporaryIdentificationOriginal) {
        try {
            backend.upload(original)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw IdentificationHandoffException(IdentificationHandoffFailure.UploadFailed)
        }
    }

    private suspend fun createRequest(
        owner: AccountId,
        requestId: IdentificationRequestId,
        request: IdentificationRequestDto,
    ) {
        try {
            backend.createRequest(owner, requestId.value, request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IdentificationHandoffException) {
            throw error
        } catch (_: Exception) {
            throw IdentificationHandoffException(IdentificationHandoffFailure.RequestFailed)
        }
    }

    private data class RequestKey(
        val owner: AccountId,
        val requestId: IdentificationRequestId,
    )
}

private fun PhotoSubmission.request(
    owner: AccountId,
    requestId: IdentificationRequestId,
): IdentificationRequestDto {
    val expiresAt = approvedAt.plusSeconds(disclosure.originalRetentionHours * 60L * 60L)
    val path = StorageContract.identificationOriginal(owner, requestId.value, photo.fileName())
    return IdentificationRequestDto(
        ownerUid = owner.value,
        temporaryOriginalPath = path,
        createdAt = FirestoreTimestampAdapter.fromInstant(approvedAt),
        expiresAt = FirestoreTimestampAdapter.fromInstant(expiresAt),
        revision = 1,
        expectedRevision = 0,
        idempotencyKey = requestId.value,
        updatedAt = approvedAt.toString(),
    )
}

private fun PhotoSubmission.original(
    owner: AccountId,
    requestId: IdentificationRequestId,
    expiresAt: Instant,
    bytes: ByteArray,
) =
    TemporaryIdentificationOriginal(
        path = StorageContract.identificationOriginal(owner, requestId.value, photo.fileName()),
        ownerUid = owner.value,
        requestId = requestId.value,
        contentType = photo.mime.contentType(),
        expiresAt = expiresAt,
        bytes = bytes,
    )

private fun IdentificationRequestDto.requireSameRequest(expected: IdentificationRequestDto) {
    if (ownerUid != expected.ownerUid || temporaryOriginalPath != expected.temporaryOriginalPath) {
        throw IdentificationHandoffException(IdentificationHandoffFailure.PermissionDenied)
    }
}

private fun com.planterior.helper.feature.camera.PreparedPhoto.fileName(): String =
    "original.${mime.extension()}"

private fun PhotoMime.extension(): String =
    when (this) {
        PhotoMime.Jpeg -> "jpg"
        PhotoMime.Png -> "png"
        PhotoMime.Webp -> "webp"
        PhotoMime.Heif -> "heif"
    }

private fun PhotoMime.contentType(): String =
    when (this) {
        PhotoMime.Jpeg -> "image/jpeg"
        PhotoMime.Png -> "image/png"
        PhotoMime.Webp -> "image/webp"
        PhotoMime.Heif -> "image/heif"
    }
