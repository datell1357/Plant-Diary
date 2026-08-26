package com.planterior.helper.identify

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.data.FirebasePrivateMediaGateway
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
    val functions = FirebaseFunctions.getInstance(app)
    val authorizer =
        FirebaseIdentificationRequestAuthorizer(
            FirebaseIdentificationRequestCallable { payload ->
                functions.getHttpsCallable("createIdentificationRequest").call(payload).await().data
            }
        )
    return ApprovedPhotoIdentificationHandoff(
        FirebaseIdentificationHandoffBackend(
            FirebaseAuth.getInstance(app),
            authorizer,
            FirebasePrivateMediaGateway(functions),
        ),
        PrivatePhotoBytes { uri ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
                    ?: throw IOException("Photo unavailable")
            }
        },
    )
}

private class FirebaseIdentificationHandoffBackend(
    private val auth: FirebaseAuth,
    private val authorizer: FirebaseIdentificationRequestAuthorizer,
    private val privateMedia: PrivateMediaGateway,
) : IdentificationHandoffBackend {
    override fun currentOwner(): AccountId? = auth.currentUser?.uid?.let(::AccountId)

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

    override suspend fun authorizeRequest(
        owner: AccountId,
        requestId: String,
        mediaReference: PrivateMediaReference,
        disclosureVersion: Int,
    ): IdentificationRequestAcknowledgement =
        authorizer.authorize(owner, requestId, mediaReference, disclosureVersion)
}

internal fun interface FirebaseIdentificationRequestCallable {
    suspend fun call(payload: Map<String, Any>): Any?
}

internal class FirebaseIdentificationRequestAuthorizer(
    private val callable: FirebaseIdentificationRequestCallable
) {
    suspend fun authorize(
        owner: AccountId,
        requestId: String,
        mediaReference: PrivateMediaReference,
        disclosureVersion: Int,
    ): IdentificationRequestAcknowledgement {
        val value =
            callable.call(
                mapOf(
                    "expectedOwnerUid" to owner.value,
                    "requestId" to requestId,
                    "mediaReference" to mediaReference.wireValue(),
                    "disclosureVersion" to disclosureVersion,
                )
            )
        return decodeIdentificationRequestAcknowledgement(value)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener(continuation::resume)
    addOnFailureListener(continuation::resumeWithException)
    addOnCanceledListener { continuation.cancel() }
}
