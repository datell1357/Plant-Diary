package com.planterior.helper.core.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val MAX_PRIVATE_MEDIA_BYTES = 20 * 1024 * 1024
private const val PRIVATE_MEDIA_PREFIX = "private-media-v2"
private const val PRIVATE_MEDIA_SEAL_CONTENT_TYPE = "application/x.planterior-private-media-seal"
private val opaqueId = Regex("^[A-Za-z0-9_-]{8,128}$")
private val ownerId = Regex("^[A-Za-z0-9_-]{1,128}$")
private val generation = Regex("^[1-9][0-9]*$")
private val supportedContentTypes =
    setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")

enum class PrivateMediaKind(val wireName: String) {
    IDENTIFICATION_ORIGINAL("IDENTIFICATION_ORIGINAL"),
    PLANT_PHOTO("PLANT_PHOTO"),
}

data class PrivateMediaReference(val reservationId: String, val generation: String) {
    init {
        require(reservationId.matches(opaqueId))
        require(generation.matches(com.planterior.helper.core.data.generation))
    }

    val storagePath: String
        get() = "$PRIVATE_MEDIA_PREFIX/$reservationId"

    fun wireValue(): Map<String, String> =
        mapOf("reservationId" to reservationId, "generation" to generation)

    companion object {
        fun fromWireValue(value: Any?): PrivateMediaReference {
            val map = value.strictMap(setOf("reservationId", "generation"))
            return PrivateMediaReference(map.string("reservationId"), map.string("generation"))
        }

        fun fromStorageObject(path: String, generation: String): PrivateMediaReference {
            val segments = path.split('/')
            require(segments.size == 2 && segments[0] == PRIVATE_MEDIA_PREFIX)
            return PrivateMediaReference(segments[1], generation)
        }
    }
}

data class PrivateMediaUpload(
    val expectedOwnerUid: String,
    val mediaKind: PrivateMediaKind,
    val contentType: String,
    val bytes: ByteArray,
    val idempotencyKey: String,
) {
    init {
        require(expectedOwnerUid.matches(ownerId))
        require(contentType in supportedContentTypes)
        require(bytes.size in 1..MAX_PRIVATE_MEDIA_BYTES)
        require(idempotencyKey.matches(opaqueId))
    }

    override fun equals(other: Any?): Boolean =
        other is PrivateMediaUpload &&
            expectedOwnerUid == other.expectedOwnerUid &&
            mediaKind == other.mediaKind &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes) &&
            idempotencyKey == other.idempotencyKey

    override fun hashCode(): Int = bytes.contentHashCode()
}

data class PrivateMediaObjectMetadata(
    val path: String,
    val generation: String,
    val byteSize: Long,
    val contentType: String,
    val customMetadata: Map<String, String>,
) {
    fun requireOwnerReadable(reference: PrivateMediaReference, ownerUid: String) {
        require(path == reference.storagePath && generation == reference.generation)
        require(byteSize in 1..MAX_PRIVATE_MEDIA_BYTES.toLong())
        require(
            contentType in supportedContentTypes && contentType != PRIVATE_MEDIA_SEAL_CONTENT_TYPE
        )
        require(customMetadata.keys == setOf("ownerUid", "reservationId"))
        require(customMetadata["ownerUid"] == ownerUid)
        require(customMetadata["reservationId"] == reference.reservationId)
    }
}

fun interface PrivateMediaGateway {
    suspend fun upload(request: PrivateMediaUpload): PrivateMediaReference
}

enum class PrivateMediaGatewayError {
    RESERVATION_FAILED,
    UPLOAD_REJECTED,
    COMMIT_FAILED,
    MALFORMED_RESPONSE,
}

class PrivateMediaGatewayException(val reason: PrivateMediaGatewayError) : Exception(reason.name)

fun interface PrivateMediaCallable {
    suspend fun call(name: String, payload: Map<String, Any>): Any?
}

sealed interface SignedPutResult {
    data object Uploaded : SignedPutResult

    data object PreconditionFailed : SignedPutResult

    data class Indeterminate(val cause: IOException) : SignedPutResult
}

fun interface SignedPutTransport {
    suspend fun put(
        url: String,
        headers: Map<String, String>,
        bytes: ByteArray,
    ): SignedPutResult
}

class FirebasePrivateMediaGateway(
    private val callable: PrivateMediaCallable,
    private val putTransport: SignedPutTransport,
) : PrivateMediaGateway {
    constructor(
        functions: FirebaseFunctions
    ) : this(
        FirebasePrivateMediaCallable(functions),
        HttpSignedPutTransport(),
    )

    override suspend fun upload(request: PrivateMediaUpload): PrivateMediaReference {
        val reserved = reserve(request)
        when (val result = putTransport.put(reserved.url, reserved.headers, request.bytes)) {
            SignedPutResult.Uploaded,
            SignedPutResult.PreconditionFailed,
            is SignedPutResult.Indeterminate -> Unit
        }
        return commit(request, reserved.reservationId)
    }

    private suspend fun reserve(request: PrivateMediaUpload): ReservedUpload {
        val value =
            callSafely(PrivateMediaGatewayError.RESERVATION_FAILED) {
                callable.call(
                    "reservePrivateMediaUpload",
                    mapOf(
                        "expectedOwnerUid" to request.expectedOwnerUid,
                        "mediaKind" to request.mediaKind.wireName,
                        "contentType" to request.contentType,
                        "byteSize" to request.bytes.size.toLong(),
                        "idempotencyKey" to request.idempotencyKey,
                    ),
                )
            }
        return malformed {
            val root = value.strictMap(setOf("reservationId", "upload"))
            val reservationId = root.string("reservationId")
            require(reservationId.matches(opaqueId))
            val upload =
                root["upload"].strictMap(
                    setOf("method", "url", "expiresAtMillis", "requiredHeaders")
                )
            require(upload.string("method") == "PUT")
            val url = upload.string("url")
            require(URI(url).scheme == "https")
            require(upload.long("expiresAtMillis") > 0)
            val headers = upload["requiredHeaders"].stringMap()
            val expected =
                mapOf(
                    "content-length" to request.bytes.size.toString(),
                    "content-type" to request.contentType,
                    "x-goog-if-generation-match" to "0",
                    "x-goog-meta-owner-uid" to request.expectedOwnerUid,
                    "x-goog-meta-reservation-id" to reservationId,
                )
            require(headers == expected)
            ReservedUpload(reservationId, url, headers)
        }
    }

    private suspend fun commit(
        request: PrivateMediaUpload,
        reservationId: String,
    ): PrivateMediaReference {
        val value =
            callSafely(PrivateMediaGatewayError.COMMIT_FAILED) {
                callable.call(
                    "commitPrivateMediaReservation",
                    mapOf(
                        "expectedOwnerUid" to request.expectedOwnerUid,
                        "reservationId" to reservationId,
                    ),
                )
            }
        return malformed {
            val root = value.strictMap(setOf("reference", "mediaKind", "contentType", "byteSize"))
            val reference = PrivateMediaReference.fromWireValue(root["reference"])
            require(reference.reservationId == reservationId)
            require(root.string("mediaKind") == request.mediaKind.wireName)
            require(root.string("contentType") == request.contentType)
            require(root.long("byteSize") == request.bytes.size.toLong())
            reference
        }
    }

    private suspend fun callSafely(
        reason: PrivateMediaGatewayError,
        block: suspend () -> Any?,
    ): Any? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw PrivateMediaGatewayException(reason)
        }

    private inline fun <T> malformed(block: () -> T): T =
        try {
            block()
        } catch (error: PrivateMediaGatewayException) {
            throw error
        } catch (_: Exception) {
            throw PrivateMediaGatewayException(PrivateMediaGatewayError.MALFORMED_RESPONSE)
        }

    private data class ReservedUpload(
        val reservationId: String,
        val url: String,
        val headers: Map<String, String>,
    )
}

class FirebasePrivateMediaCallable(private val functions: FirebaseFunctions) :
    PrivateMediaCallable {
    override suspend fun call(name: String, payload: Map<String, Any>): Any? =
        functions.getHttpsCallable(name).call(payload).await().data
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

private fun Any?.strictMap(keys: Set<String>): Map<*, *> {
    val map = this as? Map<*, *> ?: error("map")
    require(map.keys.all { it is String } && map.keys == keys)
    return map
}

private fun Map<*, *>.string(name: String): String = this[name] as? String ?: error(name)

private fun Map<*, *>.long(name: String): Long {
    val value = this[name] as? Number ?: error(name)
    val long = value.toLong()
    require(value.toDouble() == long.toDouble())
    return long
}

private fun Any?.stringMap(): Map<String, String> {
    val map = this as? Map<*, *> ?: error("headers")
    require(map.entries.all { it.key is String && it.value is String })
    return map.entries.associate { it.key as String to it.value as String }
}
