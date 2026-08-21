package com.planterior.helper.core.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

const val INVENTORY_CONTRACT_VERSION = 3

fun interface InventoryCallable {
    suspend fun call(accountId: AccountId): Any?
}

class FirebaseInventoryCallable(private val functions: FirebaseFunctions) : InventoryCallable {
    override suspend fun call(accountId: AccountId): Any? =
        functions
            .getHttpsCallable("loadInventory")
            .call(mapOf("expectedOwnerUid" to accountId.value))
            .awaitInventoryTask()
            .data
}

class InconsistentInventoryException(details: String) :
    IOException("Authoritative inventory is inconsistent: $details")

enum class AuthoritativeInventoryCondition(val wireValue: String) {
    REGISTERED_PLANT("registered-plant")
}

enum class AuthoritativeInventoryAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

data class AuthoritativeCatalogItem(
    val itemId: ItemId,
    val name: String,
    val description: String,
    val category: ItemCategory,
    val mediaIdentity: CatalogMediaIdentity,
    val acquisitionCondition: AuthoritativeInventoryCondition?,
    val revision: Revision,
    val updatedAtEpochMillis: Long,
)

data class AuthoritativeOwnedCatalogSnapshot(
    val name: String,
    val category: ItemCategory,
    val mediaIdentity: CatalogMediaIdentity,
    val catalogRevision: Revision,
)

data class AuthoritativeOwnedItem(
    val itemId: ItemId,
    val acquiredAtEpochMillis: Long,
    val applied: Boolean,
    val revision: Revision,
    val availability: AuthoritativeInventoryAvailability,
    val catalogSnapshot: AuthoritativeOwnedCatalogSnapshot?,
)

data class AuthoritativeInventory(
    val contractVersion: Int,
    val accountId: AccountId,
    val catalog: List<AuthoritativeCatalogItem>,
    val owned: List<AuthoritativeOwnedItem>,
    val registeredPlantCount: Int,
    val loadedAtEpochMillis: Long,
    val partial: Boolean,
    val generation: Long,
    val snapshotHash: String,
)

class AuthoritativeInventoryReader(private val callable: InventoryCallable) {
    constructor(functions: FirebaseFunctions) : this(FirebaseInventoryCallable(functions))

    suspend fun read(accountId: AccountId): AuthoritativeInventory =
        parse(accountId, callable.call(accountId))

    fun parse(accountId: AccountId, value: Any?): AuthoritativeInventory {
        val response = value.inventoryRecord("response")
        response.inventoryExactFields(RESPONSE_FIELDS, "response")
        val version = response.inventorySafeInt("contractVersion", 1)
        if (version != INVENTORY_CONTRACT_VERSION)
            inventoryInconsistent("contractVersion is unsupported")
        if (response.inventoryString("ownerUid") != accountId.value) {
            inventoryInconsistent("response owner differs")
        }
        val catalog = response.inventoryList("catalog").map(::parseCatalogItem)
        if (catalog.map { it.itemId }.toSet().size != catalog.size) {
            inventoryInconsistent("catalog item IDs are not unique")
        }
        val catalogIds = catalog.mapTo(mutableSetOf()) { it.itemId }
        val owned = response.inventoryList("owned").map(::parseOwnedItem)
        if (owned.map { it.itemId }.toSet().size != owned.size) {
            inventoryInconsistent("owned item IDs are not unique")
        }
        owned.forEach { item ->
            val public = item.itemId in catalogIds
            if ((item.availability == AuthoritativeInventoryAvailability.AVAILABLE) != public) {
                inventoryInconsistent("owned availability differs from public catalog")
            }
        }
        val partial = response.inventoryBoolean("partial")
        if (
            !partial &&
                owned.any { it.availability == AuthoritativeInventoryAvailability.UNAVAILABLE }
        ) {
            inventoryInconsistent("unavailable ownership requires a partial response")
        }
        val registeredPlantCount = response.inventorySafeInt("registeredPlantCount", 0, 200)
        val generation = response.inventorySafeLong("inventoryGeneration", 1)
        val snapshotHash = response.inventoryString("snapshotHash")
        if (!INVENTORY_HASH.matches(snapshotHash)) inventoryInconsistent("snapshotHash is invalid")
        val expectedHash =
            authoritativeInventorySnapshotHash(
                accountId,
                catalog,
                owned,
                registeredPlantCount,
                partial,
            )
        if (snapshotHash != expectedHash) inventoryInconsistent("snapshotHash differs from payload")
        return AuthoritativeInventory(
            contractVersion = version,
            accountId = accountId,
            catalog = catalog,
            owned = owned,
            registeredPlantCount = registeredPlantCount,
            loadedAtEpochMillis = response.inventorySafeLong("loadedAtEpochMillis", 0),
            partial = partial,
            generation = generation,
            snapshotHash = snapshotHash,
        )
    }

    private fun parseCatalogItem(raw: Any?): AuthoritativeCatalogItem {
        val item = raw.inventoryRecord("catalog item")
        item.inventoryExactFields(CATALOG_FIELDS, "catalog item")
        val itemId = item.inventoryItemId("itemId")
        val condition = item["acquisitionCondition"]
        val parsedCondition =
            when (condition) {
                null -> null
                "registered-plant" -> AuthoritativeInventoryCondition.REGISTERED_PLANT
                else -> inventoryInconsistent("acquisitionCondition is unsupported")
            }
        return AuthoritativeCatalogItem(
            itemId = itemId,
            name = item.inventoryBoundedString("name", 100),
            description = item.inventoryBoundedString("description", 500),
            category = item.inventoryCategory("category"),
            mediaIdentity = item.inventoryMediaIdentity("mediaIdentity", itemId),
            acquisitionCondition = parsedCondition,
            revision = Revision(item.inventorySafeLong("revision", 1)),
            updatedAtEpochMillis = item.inventorySafeLong("updatedAtEpochMillis", 0),
        )
    }

    private fun parseOwnedItem(raw: Any?): AuthoritativeOwnedItem {
        val owned = raw.inventoryRecord("owned item")
        owned.inventoryExactFields(OWNED_FIELDS, "owned item")
        val itemId = owned.inventoryItemId("itemId")
        val snapshotValue = owned["catalogSnapshot"]
        val snapshot =
            if (snapshotValue == null) null else parseOwnedCatalogSnapshot(snapshotValue, itemId)
        val availability =
            when (owned.inventoryString("availability")) {
                "AVAILABLE" -> AuthoritativeInventoryAvailability.AVAILABLE
                "UNAVAILABLE" -> AuthoritativeInventoryAvailability.UNAVAILABLE
                else -> inventoryInconsistent("availability is unsupported")
            }
        return AuthoritativeOwnedItem(
            itemId = itemId,
            acquiredAtEpochMillis = owned.inventorySafeLong("acquiredAtEpochMillis", 0),
            applied = owned.inventoryBoolean("applied"),
            revision = Revision(owned.inventorySafeLong("revision", 1)),
            availability = availability,
            catalogSnapshot = snapshot,
        )
    }

    private fun parseOwnedCatalogSnapshot(
        raw: Any?,
        itemId: ItemId,
    ): AuthoritativeOwnedCatalogSnapshot {
        val snapshot = raw.inventoryRecord("catalog snapshot")
        snapshot.inventoryExactFields(SNAPSHOT_FIELDS, "catalog snapshot")
        return AuthoritativeOwnedCatalogSnapshot(
            name = snapshot.inventoryBoundedString("name", 100),
            category = snapshot.inventoryCategory("category"),
            mediaIdentity = snapshot.inventoryMediaIdentity("mediaIdentity", itemId),
            catalogRevision = Revision(snapshot.inventorySafeLong("catalogRevision", 1)),
        )
    }
}

fun authoritativeInventorySnapshotHash(
    accountId: AccountId,
    catalog: List<AuthoritativeCatalogItem>,
    owned: List<AuthoritativeOwnedItem>,
    registeredPlantCount: Int,
    partial: Boolean,
): String {
    fun encoded(value: String?): String =
        value?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        } ?: "~"
    fun AuthoritativeCatalogItem.line(): String =
        listOf(
                "C",
                encoded(itemId.value),
                encoded(name),
                encoded(description),
                category.name,
                encoded(mediaIdentity.path),
                mediaIdentity.sha256,
                mediaIdentity.byteSize.toString(),
                mediaIdentity.mimeType,
                mediaIdentity.width.toString(),
                mediaIdentity.height.toString(),
                mediaIdentity.mediaRevision.value.toString(),
                acquisitionCondition?.wireValue ?: "~",
                revision.value.toString(),
                updatedAtEpochMillis.toString(),
            )
            .joinToString("\t")
    fun AuthoritativeOwnedItem.line(): String =
        listOf(
                "O",
                encoded(itemId.value),
                acquiredAtEpochMillis.toString(),
                if (applied) "1" else "0",
                revision.value.toString(),
                availability.name,
                encoded(catalogSnapshot?.name),
                catalogSnapshot?.category?.name ?: "~",
                encoded(catalogSnapshot?.mediaIdentity?.path),
                catalogSnapshot?.mediaIdentity?.sha256 ?: "~",
                catalogSnapshot?.mediaIdentity?.byteSize?.toString() ?: "~",
                catalogSnapshot?.mediaIdentity?.mimeType ?: "~",
                catalogSnapshot?.mediaIdentity?.width?.toString() ?: "~",
                catalogSnapshot?.mediaIdentity?.height?.toString() ?: "~",
                catalogSnapshot?.mediaIdentity?.mediaRevision?.value?.toString() ?: "~",
                catalogSnapshot?.catalogRevision?.value?.toString() ?: "~",
            )
            .joinToString("\t")
    val canonical = buildList {
        add("INVENTORY-SNAPSHOT-V3")
        add(encoded(accountId.value))
        add(registeredPlantCount.toString())
        add(if (partial) "1" else "0")
        addAll(catalog.sortedBy { it.itemId.value }.map { it.line() })
        addAll(owned.sortedBy { it.itemId.value }.map { it.line() })
    }
        .joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private val RESPONSE_FIELDS =
    setOf(
        "contractVersion",
        "ownerUid",
        "catalog",
        "owned",
        "registeredPlantCount",
        "loadedAtEpochMillis",
        "partial",
        "inventoryGeneration",
        "snapshotHash",
    )
private val CATALOG_FIELDS =
    setOf(
        "itemId",
        "name",
        "description",
        "category",
        "mediaIdentity",
        "acquisitionCondition",
        "revision",
        "updatedAtEpochMillis",
    )
private val OWNED_FIELDS =
    setOf(
        "itemId",
        "acquiredAtEpochMillis",
        "applied",
        "revision",
        "availability",
        "catalogSnapshot",
    )
private val SNAPSHOT_FIELDS = setOf("name", "category", "mediaIdentity", "catalogRevision")
private val MEDIA_IDENTITY_FIELDS =
    setOf("path", "sha256", "byteSize", "mimeType", "width", "height", "mediaRevision")
private val INVENTORY_OPAQUE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
private val INVENTORY_HASH = Regex("^[a-f0-9]{64}$")
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

private fun Any?.inventoryRecord(label: String): Map<*, *> =
    this as? Map<*, *> ?: inventoryInconsistent("$label is not an object")

private fun Map<*, *>.inventoryExactFields(expected: Set<String>, label: String) {
    if (keys.any { it !is String } || keys != expected)
        inventoryInconsistent("$label fields differ")
}

private fun Map<*, *>.inventoryString(field: String): String =
    this[field] as? String ?: inventoryInconsistent("$field is not a string")

private fun Map<*, *>.inventoryBoundedString(field: String, maximum: Int): String {
    val value = inventoryString(field)
    val count = value.codePointCount(0, value.length)
    if (count !in 1..maximum) inventoryInconsistent("$field is outside the text bound")
    return value
}

private fun Map<*, *>.inventoryItemId(field: String): ItemId {
    val value = inventoryString(field)
    if (!INVENTORY_OPAQUE_ID.matches(value)) inventoryInconsistent("$field is invalid")
    return ItemId(value)
}

private fun Map<*, *>.inventoryCategory(field: String): ItemCategory =
    when (inventoryString(field)) {
        "BACKGROUND" -> ItemCategory.BACKGROUND
        "FURNITURE" -> ItemCategory.FURNITURE
        "DECORATION" -> ItemCategory.DECORATION
        else -> inventoryInconsistent("$field is unsupported")
    }

private fun Map<*, *>.inventoryMediaIdentity(
    field: String,
    itemId: ItemId,
): CatalogMediaIdentity {
    val value = this[field].inventoryRecord(field)
    value.inventoryExactFields(MEDIA_IDENTITY_FIELDS, field)
    val identity = runCatching {
        CatalogMediaIdentity(
            path = value.inventoryString("path"),
            sha256 = value.inventoryString("sha256"),
            byteSize = value.inventorySafeLong("byteSize", 1),
            mimeType = value.inventoryString("mimeType"),
            width = value.inventorySafeInt("width", 1, 32_768),
            height = value.inventorySafeInt("height", 1, 32_768),
            mediaRevision = Revision(value.inventorySafeLong("mediaRevision", 1)),
        )
    }
        .getOrElse { inventoryInconsistent("$field is invalid") }
    if (!identity.path.startsWith("catalog-assets/${itemId.value}/")) {
        inventoryInconsistent("$field item path differs")
    }
    return identity
}

private fun Map<*, *>.inventoryBoolean(field: String): Boolean =
    this[field] as? Boolean ?: inventoryInconsistent("$field is not a boolean")

private fun Map<*, *>.inventoryList(field: String): List<*> =
    this[field] as? List<*> ?: inventoryInconsistent("$field is not a list")

private fun Map<*, *>.inventorySafeLong(field: String, minimum: Long): Long {
    val number = this[field] as? Number ?: inventoryInconsistent("$field is not numeric")
    val value = number.toLong()
    if (number.toDouble() != value.toDouble() || value !in minimum..MAX_SAFE_INTEGER) {
        inventoryInconsistent("$field is outside the safe range")
    }
    return value
}

private fun Map<*, *>.inventorySafeInt(
    field: String,
    minimum: Int,
    maximum: Int = Int.MAX_VALUE,
): Int {
    val value = inventorySafeLong(field, minimum.toLong())
    if (value > maximum) inventoryInconsistent("$field is outside the integer range")
    return value.toInt()
}

private fun inventoryInconsistent(details: String): Nothing =
    throw InconsistentInventoryException(details)

private suspend fun <T> Task<T>.awaitInventoryTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.cancel(CancellationException("Firebase task cancelled"))
            }
        }
    }
