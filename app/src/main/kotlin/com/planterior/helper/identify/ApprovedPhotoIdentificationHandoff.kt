package com.planterior.helper.identify

import com.planterior.helper.core.data.FirestoreTimestampAdapter
import com.planterior.helper.core.data.IdentificationRequestDto
import com.planterior.helper.core.data.PrivateMediaReference
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoSubmission
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
    val ownerUid: String,
    val requestId: String,
    val contentType: String,
    val bytes: ByteArray,
)

interface IdentificationHandoffBackend {
    fun currentOwner(): AccountId?

    suspend fun findRequest(owner: AccountId, requestId: String): IdentificationRequestDto?

    suspend fun upload(original: TemporaryIdentificationOriginal): PrivateMediaReference

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
            val existing = requestLookup(owner, requestId)
            if (existing != null) {
                existing.requireSameOwner(owner, requestId)
                completed += key
                return
            }
            val bytes = readPhoto(submission)
            val mediaReference = upload(submission.original(owner, requestId, bytes))
            createRequest(owner, requestId, submission.request(owner, requestId, mediaReference))
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

    private suspend fun upload(original: TemporaryIdentificationOriginal): PrivateMediaReference {
        try {
            return backend.upload(original)
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
    mediaReference: PrivateMediaReference,
): IdentificationRequestDto {
    val expiresAt = approvedAt.plusSeconds(disclosure.originalRetentionHours * 60L * 60L)
    return IdentificationRequestDto(
        ownerUid = owner.value,
        mediaReference = mediaReference,
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
    bytes: ByteArray,
) =
    TemporaryIdentificationOriginal(
        ownerUid = owner.value,
        requestId = requestId.value,
        contentType = photo.mime.contentType(),
        bytes = bytes,
    )

private fun IdentificationRequestDto.requireSameOwner(
    owner: AccountId,
    requestId: IdentificationRequestId,
) {
    if (ownerUid != owner.value || idempotencyKey != requestId.value) {
        throw IdentificationHandoffException(IdentificationHandoffFailure.PermissionDenied)
    }
}

private fun PhotoMime.contentType(): String =
    when (this) {
        PhotoMime.Jpeg -> "image/jpeg"
        PhotoMime.Png -> "image/png"
        PhotoMime.Webp -> "image/webp"
        PhotoMime.Heif -> "image/heif"
    }
