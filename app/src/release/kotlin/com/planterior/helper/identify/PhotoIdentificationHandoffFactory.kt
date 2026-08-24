package com.planterior.helper.identify

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.data.FirebasePrivateMediaGateway
import com.planterior.helper.core.data.FirestoreContract
import com.planterior.helper.core.data.IdentificationRequestDto
import com.planterior.helper.core.data.PrivateMediaGateway
import com.planterior.helper.core.data.PrivateMediaKind
import com.planterior.helper.core.data.PrivateMediaReference
import com.planterior.helper.core.data.PrivateMediaUpload
import com.planterior.helper.core.model.AccountId
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal fun photoIdentificationHandoff(context: Context): PhotoIdentificationHandoff {
    val app = FirebaseApp.getInstance()
    val backend =
        FirebaseIdentificationHandoffBackend(
            FirebaseAuth.getInstance(app),
            FirebaseFirestore.getInstance(app),
            FirebasePrivateMediaGateway(FirebaseFunctions.getInstance(app)),
        )
    return ApprovedPhotoIdentificationHandoff(
        backend,
        PrivatePhotoBytes { uri ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri.toUri())?.use {
                    it.readBytes()
                } ?: throw IOException("Photo unavailable")
            }
        },
    )
}

private class FirebaseIdentificationHandoffBackend(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val privateMedia: PrivateMediaGateway,
) : IdentificationHandoffBackend {
    override fun currentOwner(): AccountId? = auth.currentUser?.uid?.let(::AccountId)

    override suspend fun findRequest(
        owner: AccountId,
        requestId: String,
    ): IdentificationRequestDto? {
        val snapshot = requestReference(owner, requestId).get().await()
        return if (snapshot.exists()) snapshot.identificationRequest() else null
    }

    override suspend fun upload(original: TemporaryIdentificationOriginal): PrivateMediaReference =
        privateMedia.upload(
            PrivateMediaUpload(
                expectedOwnerUid = original.ownerUid,
                mediaKind = PrivateMediaKind.IDENTIFICATION_ORIGINAL,
                contentType = original.contentType,
                bytes = original.bytes,
                idempotencyKey = original.requestId,
            )
        )

    override suspend fun createRequest(
        owner: AccountId,
        requestId: String,
        request: IdentificationRequestDto,
    ) {
        val reference = requestReference(owner, requestId)
        firestore
            .runTransaction { transaction ->
                val existing = transaction.get(reference)
                if (existing.exists()) {
                    val stored = existing.identificationRequest()
                    if (
                        stored.ownerUid != request.ownerUid ||
                            stored.mediaReference != request.mediaReference
                    ) {
                        throw IdentificationHandoffException(
                            IdentificationHandoffFailure.PermissionDenied
                        )
                    }
                } else {
                    transaction.set(
                        reference,
                        mapOf(
                            "ownerUid" to request.ownerUid,
                            "mediaReference" to request.mediaReference.wireValue(),
                            "createdAt" to request.createdAt,
                            "expiresAt" to request.expiresAt,
                            "revision" to request.revision,
                            "expectedRevision" to request.expectedRevision,
                            "idempotencyKey" to request.idempotencyKey,
                            "updatedAt" to request.updatedAt,
                        ),
                    )
                }
            }
            .await()
    }

    private fun requestReference(owner: AccountId, requestId: String) =
        firestore.document(
            FirestoreContract.userDocument(
                owner,
                FirestoreContract.UserCollection.IDENTIFICATION_REQUESTS,
                requestId,
            )
        )
}

private fun DocumentSnapshot.identificationRequest() =
    IdentificationRequestDto(
        ownerUid = getString("ownerUid") ?: throw malformedRequest(),
        mediaReference =
            PrivateMediaReference.fromWireValue(get("mediaReference") ?: throw malformedRequest()),
        createdAt = getTimestamp("createdAt") ?: throw malformedRequest(),
        expiresAt = getTimestamp("expiresAt") ?: throw malformedRequest(),
        revision = getLong("revision") ?: throw malformedRequest(),
        expectedRevision = getLong("expectedRevision") ?: throw malformedRequest(),
        idempotencyKey = getString("idempotencyKey") ?: throw malformedRequest(),
        updatedAt = getString("updatedAt") ?: throw malformedRequest(),
    )

private fun malformedRequest() =
    IdentificationHandoffException(IdentificationHandoffFailure.RequestFailed)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener(continuation::resume)
    addOnFailureListener(continuation::resumeWithException)
    addOnCanceledListener { continuation.cancel() }
}
