package com.planterior.helper.identify

import com.planterior.helper.core.data.PrivateMediaReference
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val IDENTIFICATION_DISCLOSURE_VERSION = 1

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

data class IdentificationRequestAcknowledgement(
    val requestId: String,
    val disclosureVersion: Int,
    val acknowledgedAtMillis: Long,
    val createdAtMillis: Long,
    val hardExpiresAtMillis: Long,
)

interface IdentificationHandoffBackend {
    fun currentOwner(): AccountId?

    suspend fun upload(original: TemporaryIdentificationOriginal): PrivateMediaReference

    suspend fun authorizeRequest(
        owner: AccountId,
        requestId: String,
        mediaReference: PrivateMediaReference,
        disclosureVersion: Int,
    ): IdentificationRequestAcknowledgement
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

/**
 * The app owns only reserve/upload/commit of the private original. Authorization of that original
 * into an identification request is callable-only; repeating the same request id after response
 * loss is safe because both media reservation and authorization are idempotent server operations.
 */
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
            val bytes = readPhoto(submission)
            val mediaReference = upload(submission.original(owner, requestId, bytes))
            val acknowledgement = authorize(owner, requestId, mediaReference)
            if (
                acknowledgement.requestId != requestId.value ||
                    acknowledgement.disclosureVersion != IDENTIFICATION_DISCLOSURE_VERSION ||
                    acknowledgement.hardExpiresAtMillis - acknowledgement.createdAtMillis !=
                        86_400_000L ||
                    acknowledgement.acknowledgedAtMillis < acknowledgement.createdAtMillis
            ) {
                throw IdentificationHandoffException(IdentificationHandoffFailure.RequestFailed)
            }
            completed += key
        }
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

    private suspend fun upload(original: TemporaryIdentificationOriginal): PrivateMediaReference =
        try {
            backend.upload(original)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw IdentificationHandoffException(IdentificationHandoffFailure.UploadFailed)
        }

    private suspend fun authorize(
        owner: AccountId,
        requestId: IdentificationRequestId,
        mediaReference: PrivateMediaReference,
    ): IdentificationRequestAcknowledgement =
        try {
            backend.authorizeRequest(
                owner,
                requestId.value,
                mediaReference,
                IDENTIFICATION_DISCLOSURE_VERSION,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IdentificationHandoffException) {
            throw error
        } catch (_: Exception) {
            throw IdentificationHandoffException(IdentificationHandoffFailure.RequestFailed)
        }

    private data class RequestKey(
        val owner: AccountId,
        val requestId: IdentificationRequestId,
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

private fun PhotoMime.contentType(): String =
    when (this) {
        PhotoMime.Jpeg -> "image/jpeg"
        PhotoMime.Png -> "image/png"
        PhotoMime.Webp -> "image/webp"
        PhotoMime.Heif -> "image/heif"
    }
