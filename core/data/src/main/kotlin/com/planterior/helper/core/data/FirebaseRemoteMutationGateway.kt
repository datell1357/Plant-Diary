package com.planterior.helper.core.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

fun interface OwnerMutationCallable {
    suspend fun call(data: Map<String, Any?>): Map<String, Any>
}

class FirebaseRemoteMutationGateway(private val callable: OwnerMutationCallable) :
    RemoteMutationGateway {
    constructor(
        functions: FirebaseFunctions
    ) : this(
        OwnerMutationCallable { data ->
            @Suppress("UNCHECKED_CAST")
            (functions.getHttpsCallable("applyRevisionedOwnerWrite").call(data).await().data
                as? Map<String, Any>) ?: emptyMap()
        }
    )

    override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
        val payload =
            runCatching { Json.parseToJsonElement(command.draftPayload).toPlatformValue() }
                .getOrNull() as? Map<*, *> ?: return RemoteMutationResult.Failed("INVALID_PAYLOAD")
        @Suppress("UNCHECKED_CAST")
        val request =
            mapOf(
                "expectedOwnerUid" to command.accountId.value,
                "collection" to command.aggregateType,
                "documentId" to command.aggregateId,
                "mutationType" to command.mutationType,
                "expectedRevision" to command.expectedRevision.value,
                "idempotencyKey" to command.operationId.value,
                "payload" to payload as Map<String, Any?>,
            )
        val response =
            try {
                callable.call(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: FirebaseFunctionsException) {
                return RemoteMutationResult.Failed(error.code.name)
            } catch (_: Exception) {
                return RemoteMutationResult.Failed("UNAVAILABLE")
            }
        val kind = response["kind"] as? String
        val revision = (response["revision"] as? Number)?.toLong()
        val actualRevision = (response["actualRevision"] as? Number)?.toLong()
        return when {
            kind == "applied" && revision != null && revision >= 1 ->
                RemoteMutationResult.Applied(revision)
            kind == "duplicate" && revision != null && revision >= 1 ->
                RemoteMutationResult.Duplicate(revision)
            kind == "conflict" && actualRevision != null && actualRevision >= 0 ->
                RemoteMutationResult.Conflict(actualRevision)
            else -> RemoteMutationResult.Failed("MALFORMED_RESPONSE")
        }
    }
}

private fun JsonElement.toPlatformValue(): Any? =
    when (this) {
        JsonNull -> null
        is JsonObject -> entries.associate { it.key to it.value.toPlatformValue() }
        is JsonArray -> map { it.toPlatformValue() }
        is JsonPrimitive ->
            if (isString) contentOrNull
            else booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
    }

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
