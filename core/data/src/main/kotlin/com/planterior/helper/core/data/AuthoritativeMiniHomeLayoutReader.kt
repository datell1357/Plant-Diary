package com.planterior.helper.core.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.model.AccountId
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface MiniHomeLayoutCallable {
    suspend fun call(accountId: AccountId): Any?
}

class FirebaseMiniHomeLayoutCallable(private val functions: FirebaseFunctions) :
    MiniHomeLayoutCallable {
    override suspend fun call(accountId: AccountId): Any? =
        functions
            .getHttpsCallable("loadMiniHomeLayout")
            .call(mapOf("expectedOwnerUid" to accountId.value))
            .await()
            .data
}

class InconsistentMiniHomeLayoutException(details: String) :
    IOException("Authoritative mini-home layout is inconsistent: $details")

data class AuthoritativeMiniHomePlacement(
    val id: String,
    val plantId: String?,
    val itemId: String?,
    val normalizedX: Double,
    val normalizedY: Double,
    val zIndex: Int,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

data class AuthoritativeMiniHomeLayout(
    val generation: Long,
    val id: String,
    val name: String,
    val placedPlantCount: Int,
    val revision: Long,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val requestHash: String,
    val updatedAtEpochMillis: Long,
    val placements: List<AuthoritativeMiniHomePlacement>,
)

sealed interface AuthoritativeMiniHomeLayoutRead {
    val accountId: AccountId

    data class Missing(
        override val accountId: AccountId,
        val generation: Long,
        val tombstoneId: String,
        val updatedAtEpochMillis: Long,
    ) : AuthoritativeMiniHomeLayoutRead

    data class Present(
        override val accountId: AccountId,
        val layout: AuthoritativeMiniHomeLayout,
    ) : AuthoritativeMiniHomeLayoutRead
}

class AuthoritativeMiniHomeLayoutReader(private val callable: MiniHomeLayoutCallable) {
    constructor(functions: FirebaseFunctions) : this(FirebaseMiniHomeLayoutCallable(functions))

    suspend fun read(accountId: AccountId): AuthoritativeMiniHomeLayoutRead =
        parse(accountId, callable.call(accountId))

    fun parse(accountId: AccountId, value: Any?): AuthoritativeMiniHomeLayoutRead {
        val response = value.record("response")
        return when (response.string("kind")) {
            "missing" -> {
                response.requireExactFields(MISSING_FIELDS, "missing response")
                response.requireOwner(accountId, "response")
                AuthoritativeMiniHomeLayoutRead.Missing(
                    accountId,
                    response.safeLong("generation", minimum = 1),
                    response.operationId("tombstoneId"),
                    response.safeLong("updatedAtEpochMillis", minimum = 0),
                )
            }
            "present" -> parsePresent(accountId, response)
            else -> inconsistent("response kind is unsupported")
        }
    }

    private fun parsePresent(
        accountId: AccountId,
        response: Map<*, *>,
    ): AuthoritativeMiniHomeLayoutRead.Present {
        response.requireExactFields(PRESENT_FIELDS, "present response")
        response.requireOwner(accountId, "response")
        val generation = response.safeLong("generation", minimum = 1)
        val homeId = response.opaqueId("miniHomeId")
        val revision = response.safeLong("revision", minimum = 1)
        val expectedRevision = response.safeLong("expectedRevision", minimum = 0)
        if (expectedRevision != revision - 1) inconsistent("home revision lineage is invalid")
        val idempotencyKey = response.operationId("idempotencyKey")
        val requestHash = response.string("requestHash")
        if (!HASH.matches(requestHash)) inconsistent("requestHash is invalid")
        val name = response.string("name")
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME_CODE_POINTS) {
            inconsistent("name is invalid")
        }
        val placedPlantCount = response.safeInt("placedPlantCount", minimum = 0)
        val placementCount = response.safeInt("placementCount", minimum = 0)
        if (placementCount > MAX_PLACEMENTS) inconsistent("placementCount exceeds the bound")
        val updatedAt = response.safeLong("updatedAtEpochMillis", minimum = 0)
        val rawPlacements =
            response["placements"] as? List<*> ?: inconsistent("placements is not a list")
        if (rawPlacements.size != placementCount) inconsistent("placementCount does not match rows")
        val placements = rawPlacements.mapIndexed { index, raw ->
            parsePlacement(
                accountId = accountId,
                homeId = homeId,
                homeRevision = revision,
                homeIdempotencyKey = idempotencyKey,
                expectedZIndex = index,
                raw = raw,
            )
        }
        if (placements.map { it.id }.toSet().size != placements.size) {
            inconsistent("placement IDs are not unique")
        }
        if (placements.count { it.plantId != null } != placedPlantCount) {
            inconsistent("placedPlantCount does not match rows")
        }
        return AuthoritativeMiniHomeLayoutRead.Present(
            accountId,
            AuthoritativeMiniHomeLayout(
                generation = generation,
                id = homeId,
                name = name,
                placedPlantCount = placedPlantCount,
                revision = revision,
                expectedRevision = expectedRevision,
                idempotencyKey = idempotencyKey,
                requestHash = requestHash,
                updatedAtEpochMillis = updatedAt,
                placements = placements,
            ),
        )
    }

    private fun parsePlacement(
        accountId: AccountId,
        homeId: String,
        homeRevision: Long,
        homeIdempotencyKey: String,
        expectedZIndex: Int,
        raw: Any?,
    ): AuthoritativeMiniHomePlacement {
        val placement = raw.record("placement")
        placement.requireExactFields(PLACEMENT_FIELDS, "placement")
        placement.requireOwner(accountId, "placement")
        if (placement.string("miniHomeId") != homeId) inconsistent("placement home differs")
        val layoutRevision = placement.safeLong("layoutRevision", minimum = 1)
        val revision = placement.safeLong("revision", minimum = 1)
        val expectedRevision = placement.safeLong("expectedRevision", minimum = 0)
        if (
            layoutRevision != homeRevision ||
                revision != homeRevision ||
                expectedRevision != homeRevision - 1
        ) {
            inconsistent("placement revision differs")
        }
        if (placement.operationId("idempotencyKey") != homeIdempotencyKey) {
            inconsistent("placement operation differs")
        }
        val plantId = placement.optionalOpaqueId("plantId")
        val itemId = placement.optionalOpaqueId("itemId")
        if ((plantId == null) == (itemId == null)) inconsistent("placement target is invalid")
        val normalizedX = placement.finiteDouble("normalizedX")
        val normalizedY = placement.finiteDouble("normalizedY")
        if (normalizedX !in 0.0..1.0 || normalizedY !in 0.0..1.0) {
            inconsistent("placement coordinates are invalid")
        }
        val zIndex = placement.safeInt("zIndex", minimum = 0)
        if (zIndex != expectedZIndex) inconsistent("placement depth is partial or unordered")
        return AuthoritativeMiniHomePlacement(
            id = placement.opaqueId("placementId"),
            plantId = plantId,
            itemId = itemId,
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            zIndex = zIndex,
            revision = revision,
            updatedAtEpochMillis = placement.safeLong("updatedAtEpochMillis", minimum = 0),
        )
    }
}

private const val MAX_PLACEMENTS = 20
private const val MAX_NAME_CODE_POINTS = 100
private val OPAQUE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
private val OPERATION_ID = Regex("^[A-Za-z0-9_-]{8,128}$")
private val HASH = Regex("^[a-f0-9]{64}$")
private val MISSING_FIELDS =
    setOf("kind", "ownerUid", "generation", "tombstoneId", "updatedAtEpochMillis")
private val PRESENT_FIELDS =
    setOf(
        "kind",
        "ownerUid",
        "generation",
        "miniHomeId",
        "name",
        "placedPlantCount",
        "placementCount",
        "revision",
        "expectedRevision",
        "idempotencyKey",
        "requestHash",
        "updatedAtEpochMillis",
        "placements",
    )
private val PLACEMENT_FIELDS =
    setOf(
        "placementId",
        "ownerUid",
        "miniHomeId",
        "layoutRevision",
        "plantId",
        "itemId",
        "normalizedX",
        "normalizedY",
        "zIndex",
        "revision",
        "expectedRevision",
        "idempotencyKey",
        "updatedAtEpochMillis",
    )

private fun Any?.record(label: String): Map<*, *> =
    this as? Map<*, *> ?: inconsistent("$label is not an object")

private fun Map<*, *>.requireExactFields(expected: Set<String>, label: String) {
    if (keys.any { it !is String } || keys != expected) inconsistent("$label fields differ")
}

private fun Map<*, *>.requireOwner(accountId: AccountId, label: String) {
    if (string("ownerUid") != accountId.value) inconsistent("$label owner differs")
}

private fun Map<*, *>.string(field: String): String =
    this[field] as? String ?: inconsistent("$field is not a string")

private fun Map<*, *>.opaqueId(field: String): String =
    string(field).takeIf(OPAQUE_ID::matches) ?: inconsistent("$field is invalid")

private fun Map<*, *>.optionalOpaqueId(field: String): String? {
    val value = this[field] ?: return null
    return (value as? String)?.takeIf(OPAQUE_ID::matches) ?: inconsistent("$field is invalid")
}

private fun Map<*, *>.operationId(field: String): String =
    string(field).takeIf(OPERATION_ID::matches) ?: inconsistent("$field is invalid")

private fun Map<*, *>.safeLong(field: String, minimum: Long): Long {
    val number = this[field] as? Number ?: inconsistent("$field is not numeric")
    val value = number.toLong()
    if (
        number.toDouble() != value.toDouble() || value < minimum || value > 9_007_199_254_740_991L
    ) {
        inconsistent("$field is outside the safe range")
    }
    return value
}

private fun Map<*, *>.safeInt(field: String, minimum: Int): Int {
    val value = safeLong(field, minimum.toLong())
    if (value > Int.MAX_VALUE) inconsistent("$field is outside the integer range")
    return value.toInt()
}

private fun Map<*, *>.finiteDouble(field: String): Double {
    val value = (this[field] as? Number)?.toDouble() ?: inconsistent("$field is not numeric")
    if (!value.isFinite()) inconsistent("$field is not finite")
    return value
}

private fun inconsistent(details: String): Nothing =
    throw InconsistentMiniHomeLayoutException(details)

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener {
        if (continuation.isActive)
            continuation.cancel(CancellationException("Firebase task cancelled"))
    }
}
