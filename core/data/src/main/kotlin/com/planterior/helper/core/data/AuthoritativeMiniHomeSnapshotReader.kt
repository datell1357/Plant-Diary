package com.planterior.helper.core.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PersonalPlantId
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

const val MINI_HOME_SNAPSHOT_CONTRACT_VERSION = 1

fun interface MiniHomeSnapshotCallable {
    suspend fun call(accountId: AccountId): Any?
}

class FirebaseMiniHomeSnapshotCallable(private val functions: FirebaseFunctions) :
    MiniHomeSnapshotCallable {
    override suspend fun call(accountId: AccountId): Any? =
        functions
            .getHttpsCallable("loadMiniHomeSnapshot")
            .call(mapOf("expectedOwnerUid" to accountId.value))
            .awaitMiniHomeSnapshotTask()
            .data
}

class InconsistentMiniHomeSnapshotException(details: String, cause: Throwable? = null) :
    IOException("Authoritative mini-home snapshot is inconsistent: $details", cause)

data class AuthoritativeMiniHomePlant(
    val id: PersonalPlantId,
    val displayName: String,
    val representativePhotoPath: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

data class AuthoritativeMiniHomeSnapshot(
    val accountId: AccountId,
    val token: String,
    val generation: Long,
    val serverReadTimeEpochMillis: Long,
    val layout: AuthoritativeMiniHomeLayoutRead,
    val inventory: AuthoritativeInventory,
    val plants: List<AuthoritativeMiniHomePlant>,
)

class AuthoritativeMiniHomeSnapshotReader(
    private val callable: MiniHomeSnapshotCallable,
    private val layoutReader: AuthoritativeMiniHomeLayoutReader =
        AuthoritativeMiniHomeLayoutReader(MiniHomeLayoutCallable { error("not called") }),
    private val inventoryReader: AuthoritativeInventoryReader =
        AuthoritativeInventoryReader(InventoryCallable { error("not called") }),
) {
    constructor(functions: FirebaseFunctions) : this(FirebaseMiniHomeSnapshotCallable(functions))

    suspend fun read(accountId: AccountId): AuthoritativeMiniHomeSnapshot {
        try {
            val response = callable.call(accountId).snapshotRecord("response")
            response.snapshotExactFields(SNAPSHOT_FIELDS, "response")
            val version = response.snapshotSafeLong("contractVersion", 1)
            if (version != MINI_HOME_SNAPSHOT_CONTRACT_VERSION.toLong()) {
                snapshotInconsistent("contractVersion is unsupported")
            }
            if (response.snapshotString("ownerUid") != accountId.value) {
                snapshotInconsistent("response owner differs")
            }
            val token = response.snapshotString("snapshotToken")
            if (!SNAPSHOT_HASH.matches(token)) snapshotInconsistent("snapshotToken is invalid")
            val layout = layoutReader.parse(accountId, response["layout"])
            val inventory = inventoryReader.parse(accountId, response["inventory"])
            val plants = response.snapshotList("plants").map { raw -> parsePlant(accountId, raw) }
            if (plants.size > MAX_SNAPSHOT_PLANTS) snapshotInconsistent("plants exceed the bound")
            if (plants.map { it.id }.toSet().size != plants.size) {
                snapshotInconsistent("plant IDs are not unique")
            }
            if (inventory.registeredPlantCount != plants.size) {
                snapshotInconsistent("registeredPlantCount differs from plants")
            }
            return AuthoritativeMiniHomeSnapshot(
                accountId = accountId,
                token = token,
                generation = response.snapshotSafeLong("snapshotGeneration", 1),
                serverReadTimeEpochMillis =
                    response.snapshotSafeLong("serverReadTimeEpochMillis", 0),
                layout = layout,
                inventory = inventory,
                plants = plants,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: InconsistentMiniHomeSnapshotException) {
            throw error
        } catch (error: InconsistentMiniHomeLayoutException) {
            throw InconsistentMiniHomeSnapshotException("layout differs", error)
        } catch (error: InconsistentInventoryException) {
            throw InconsistentMiniHomeSnapshotException("inventory differs", error)
        }
    }

    private fun parsePlant(accountId: AccountId, raw: Any?): AuthoritativeMiniHomePlant {
        val plant = raw.snapshotRecord("plant")
        plant.snapshotExactFields(PLANT_FIELDS, "plant")
        if (plant.snapshotString("ownerUid") != accountId.value) {
            snapshotInconsistent("plant owner differs")
        }
        val id = plant.snapshotString("plantId")
        if (!SNAPSHOT_OPAQUE_ID.matches(id)) snapshotInconsistent("plantId is invalid")
        val displayName = plant.snapshotString("displayName")
        if (displayName.codePointCount(0, displayName.length) !in 1..100) {
            snapshotInconsistent("plant displayName is invalid")
        }
        val photo = plant["representativePhotoPath"]
        if (photo != null && (photo !is String || photo.isEmpty() || photo.length > 500)) {
            snapshotInconsistent("plant photo path is invalid")
        }
        return AuthoritativeMiniHomePlant(
            id = PersonalPlantId(id),
            displayName = displayName,
            representativePhotoPath = photo,
            revision = plant.snapshotSafeLong("revision", 1),
            updatedAtEpochMillis = plant.snapshotSafeLong("updatedAtEpochMillis", 0),
        )
    }
}

private const val MAX_SNAPSHOT_PLANTS = 200
private const val MAX_SNAPSHOT_SAFE_INTEGER = 9_007_199_254_740_991L
private val SNAPSHOT_HASH = Regex("^[a-f0-9]{64}$")
private val SNAPSHOT_OPAQUE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
private val SNAPSHOT_FIELDS =
    setOf(
        "contractVersion",
        "ownerUid",
        "snapshotToken",
        "snapshotGeneration",
        "serverReadTimeEpochMillis",
        "layout",
        "inventory",
        "plants",
    )
private val PLANT_FIELDS =
    setOf(
        "plantId",
        "ownerUid",
        "displayName",
        "representativePhotoPath",
        "revision",
        "updatedAtEpochMillis",
    )

private fun Any?.snapshotRecord(label: String): Map<*, *> =
    this as? Map<*, *> ?: snapshotInconsistent("$label is not an object")

private fun Map<*, *>.snapshotExactFields(expected: Set<String>, label: String) {
    if (keys.any { it !is String } || keys != expected) {
        snapshotInconsistent("$label fields differ")
    }
}

private fun Map<*, *>.snapshotString(field: String): String =
    this[field] as? String ?: snapshotInconsistent("$field is not a string")

private fun Map<*, *>.snapshotList(field: String): List<*> =
    this[field] as? List<*> ?: snapshotInconsistent("$field is not a list")

private fun Map<*, *>.snapshotSafeLong(field: String, minimum: Long): Long {
    val number = this[field] as? Number ?: snapshotInconsistent("$field is not numeric")
    val value = number.toLong()
    if (number.toDouble() != value.toDouble() || value !in minimum..MAX_SNAPSHOT_SAFE_INTEGER) {
        snapshotInconsistent("$field is outside the safe range")
    }
    return value
}

private fun snapshotInconsistent(details: String): Nothing =
    throw InconsistentMiniHomeSnapshotException(details)

private suspend fun <T> Task<T>.awaitMiniHomeSnapshotTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.cancel(CancellationException("Firebase task cancelled"))
            }
        }
    }
