package com.planterior.helper.feature.identify

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PlantContentId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface IdentificationGateway {
    suspend fun identify(
        requestId: IdentificationRequestId,
        operationId: OperationId,
    ): IdentificationResult
}

class FirebaseIdentificationGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) : IdentificationGateway {
    override suspend fun identify(
        requestId: IdentificationRequestId,
        operationId: OperationId,
    ): IdentificationResult {
        val data =
            suspendCancellableCoroutine<Any?> { continuation ->
                functions
                    .getHttpsCallable("identifyPlant")
                    .call(
                        mapOf(
                            "requestId" to requestId.value,
                            "idempotencyKey" to operationId.value,
                        )
                    )
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result.data)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        return parseIdentificationResult(data)
    }
}

internal fun parseIdentificationResult(value: Any?): IdentificationResult {
    val data = value as? Map<*, *> ?: return IdentificationResult.Failed(
        IdentificationFailureReason.MALFORMED_RESPONSE
    )
    return when (data["kind"]) {
        "pending" -> IdentificationResult.Pending
        "no_candidates" -> IdentificationResult.NoCandidates
        "failed" ->
            IdentificationResult.Failed(
                when (data["reason"]) {
                    "timeout" -> IdentificationFailureReason.TIMEOUT
                    "rate_limited" -> IdentificationFailureReason.RATE_LIMITED
                    "provider_unavailable" -> IdentificationFailureReason.PROVIDER_UNAVAILABLE
                    else -> IdentificationFailureReason.MALFORMED_RESPONSE
                }
            )
        "candidates" -> parseCandidates(data["candidates"])
        else -> IdentificationResult.Failed(IdentificationFailureReason.MALFORMED_RESPONSE)
    }
}

private fun parseCandidates(value: Any?): IdentificationResult {
    val values = value as? List<*> ?: return IdentificationResult.Failed(
        IdentificationFailureReason.MALFORMED_RESPONSE
    )
    val candidates = values.mapNotNull(::parseCandidate)
    if (candidates.size != values.size || candidates.size !in 1..3) {
        return IdentificationResult.Failed(IdentificationFailureReason.MALFORMED_RESPONSE)
    }
    return runCatching { IdentificationResult.Candidates(candidates) }
        .getOrElse { IdentificationResult.Failed(IdentificationFailureReason.MALFORMED_RESPONSE) }
}

private fun parseCandidate(value: Any?): IdentificationCandidate? {
    val data = value as? Map<*, *> ?: return null
    val id = data["publicContentId"] as? String ?: return null
    val scientificName = data["scientificName"] as? String ?: return null
    val confidence = (data["confidence"] as? Number)?.toDouble() ?: return null
    return runCatching {
            IdentificationCandidate(
                publicContentId = PlantContentId(id),
                koreanName = data["koreanName"] as? String,
                commonName = data["commonName"] as? String,
                scientificName = scientificName,
                confidence = confidence,
                thumbnailUrl = data["thumbnailUrl"] as? String,
            )
        }
        .getOrNull()
}

fun Throwable.toIdentificationFailure(): IdentificationFailureReason {
    if (this is CancellationException) throw this
    if (this !is FirebaseFunctionsException) {
        return IdentificationFailureReason.PROVIDER_UNAVAILABLE
    }
    return when (code) {
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> IdentificationFailureReason.TIMEOUT
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
            IdentificationFailureReason.RATE_LIMITED
        else -> IdentificationFailureReason.PROVIDER_UNAVAILABLE
    }
}
