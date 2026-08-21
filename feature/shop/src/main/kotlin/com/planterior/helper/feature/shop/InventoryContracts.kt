package com.planterior.helper.feature.shop

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

enum class AcquisitionCondition(val wireValue: String) {
    REGISTERED_PLANT("registered-plant");

    companion object {
        fun parse(value: String): AcquisitionCondition = entries.single { it.wireValue == value }
    }
}

data class InventoryItem(
    val id: ItemId,
    val name: String,
    val description: String,
    val category: ItemCategory,
    val mediaIdentity: CatalogMediaIdentity,
    val acquisitionCondition: AcquisitionCondition?,
    val revision: Revision,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank() && name.codePointCount(0, name.length) <= 100)
        require(
            description.isNotBlank() && description.codePointCount(0, description.length) <= 500
        )
        require(revision.value >= 1)
    }

    val assetPath: String
        get() = mediaIdentity.path
}

data class OwnedCatalogSnapshot(
    val name: String,
    val category: ItemCategory,
    val mediaIdentity: CatalogMediaIdentity,
    val catalogRevision: Revision,
) {
    val assetPath: String
        get() = mediaIdentity.path
}

enum class InventoryItemAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

data class OwnedInventoryItem(
    val itemId: ItemId,
    val acquiredAt: Instant,
    val applied: Boolean,
    val revision: Revision,
    val availability: InventoryItemAvailability = InventoryItemAvailability.AVAILABLE,
    val catalogSnapshot: OwnedCatalogSnapshot? = null,
) {
    /** Todo 14 has unique ownership and no consumable stack or duplicate acquisition. */
    val quantity: Int = 1

    init {
        require(revision.value >= 1)
    }
}

data class InventorySnapshotSourceEpoch(
    val generation: Long,
    val verified: Boolean,
) {
    init {
        require(generation >= 0)
        if (verified) require(generation >= 1)
    }
}

data class InventorySnapshot(
    val accountId: AccountId,
    val catalog: List<InventoryItem>,
    val owned: List<OwnedInventoryItem>,
    val registeredPlantCount: Int,
    val loadedAt: Instant,
    val partial: Boolean = false,
    val generation: Long = 1,
    val snapshotHash: String =
        inventorySnapshotHash(accountId, catalog, owned, registeredPlantCount, partial),
    val verified: Boolean = true,
) {
    val sourceEpoch = InventorySnapshotSourceEpoch(generation, verified)

    init {
        require(registeredPlantCount in 0..200)
        require(snapshotHash.matches(Regex("^[a-f0-9]{64}$")))
        require(catalog.map { it.id }.distinct().size == catalog.size)
        require(owned.map { it.itemId }.distinct().size == owned.size)
        require(
            owned.all { ownedItem ->
                ownedItem.availability == InventoryItemAvailability.UNAVAILABLE ||
                    catalog.any { it.id == ownedItem.itemId }
            }
        )
    }
}

internal fun inventorySnapshotHash(
    accountId: AccountId,
    catalog: List<InventoryItem>,
    owned: List<OwnedInventoryItem>,
    registeredPlantCount: Int,
    partial: Boolean,
): String {
    fun encoded(value: String?): String =
        value?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
        } ?: "~"
    fun InventoryItem.line(): String =
        listOf(
                "C",
                encoded(id.value),
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
                updatedAt.toEpochMilli().toString(),
            )
            .joinToString("\t")
    fun OwnedInventoryItem.line(): String =
        listOf(
                "O",
                encoded(itemId.value),
                acquiredAt.toEpochMilli().toString(),
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
        addAll(catalog.sortedBy { it.id.value }.map { it.line() })
        addAll(owned.sortedBy { it.itemId.value }.map { it.line() })
    }
        .joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

enum class AcquisitionEligibility {
    ELIGIBLE,
    CONDITION_NOT_MET,
    ALREADY_OWNED,
}

data class InventoryEntry(
    val id: ItemId,
    val item: InventoryItem?,
    val ownership: OwnedInventoryItem?,
    val eligibility: AcquisitionEligibility,
    val applied: Boolean,
) {
    val name: String = item?.name ?: ownership?.catalogSnapshot?.name ?: "사용할 수 없는 아이템"
    val description: String =
        item?.description
            ?: if (ownership?.catalogSnapshot == null) {
                "이전에 획득한 아이템 정보를 확인할 수 없어요. 배치에서는 안전하게 제거할 수 있어요."
            } else {
                "현재 상점에서 제공하지 않는 보유 아이템이에요."
            }
    val category: ItemCategory? = item?.category ?: ownership?.catalogSnapshot?.category
    val mediaIdentity: CatalogMediaIdentity? =
        item?.mediaIdentity ?: ownership?.catalogSnapshot?.mediaIdentity
    val assetPath: String? = mediaIdentity?.path
    val unavailable: Boolean = item == null
}

object InventoryPolicy {
    private val categoryOrder =
        mapOf(
            ItemCategory.BACKGROUND to 0,
            ItemCategory.FURNITURE to 1,
            ItemCategory.DECORATION to 2,
        )

    fun shopEntries(
        snapshot: InventorySnapshot,
        category: ItemCategory?,
        query: String = "",
    ): List<InventoryEntry> = catalogEntries(snapshot, category, query)

    fun warehouseEntries(
        snapshot: InventorySnapshot,
        category: ItemCategory?,
        query: String = "",
    ): List<InventoryEntry> {
        val publicById = snapshot.catalog.associateBy { it.id }
        return snapshot.owned
            .asSequence()
            .filter { owned ->
                val item = publicById[owned.itemId]
                val ownedCategory = item?.category ?: owned.catalogSnapshot?.category
                category == null || ownedCategory == category
            }
            .map { owned ->
                val item = publicById[owned.itemId]
                InventoryEntry(
                    id = owned.itemId,
                    item = item,
                    ownership = owned,
                    eligibility = AcquisitionEligibility.ALREADY_OWNED,
                    applied = owned.applied,
                )
            }
            .filter { it.matches(query) }
            .sortedWith(
                compareBy<InventoryEntry> { if (it.unavailable) 1 else 0 }
                    .thenBy { it.category?.let(categoryOrder::getValue) ?: Int.MAX_VALUE }
                    .thenBy { it.name }
                    .thenBy { it.id.value }
            )
            .toList()
    }

    private fun catalogEntries(
        snapshot: InventorySnapshot,
        category: ItemCategory?,
        query: String,
    ): List<InventoryEntry> {
        val owned = snapshot.owned.associateBy { it.itemId }
        return snapshot.catalog
            .asSequence()
            .filter { category == null || it.category == category }
            .sortedWith(
                compareBy<InventoryItem> { categoryOrder.getValue(it.category) }
                    .thenBy { it.name }
                    .thenBy { it.id.value }
            )
            .map { item ->
                val ownership = owned[item.id]
                InventoryEntry(
                    id = item.id,
                    item = item,
                    ownership = ownership,
                    eligibility =
                        when {
                            ownership != null -> AcquisitionEligibility.ALREADY_OWNED
                            item.acquisitionCondition == AcquisitionCondition.REGISTERED_PLANT &&
                                snapshot.registeredPlantCount == 0 ->
                                AcquisitionEligibility.CONDITION_NOT_MET
                            else -> AcquisitionEligibility.ELIGIBLE
                        },
                    applied = ownership?.applied == true,
                )
            }
            .filter { it.matches(query) }
            .toList()
    }

    private fun InventoryEntry.matches(query: String): Boolean {
        val needle = query.trim()
        return needle.isEmpty() ||
            name.contains(needle, ignoreCase = true) ||
            description.contains(needle, ignoreCase = true) ||
            id.value.contains(needle, ignoreCase = true)
    }

    fun conditionLabel(item: InventoryItem): String =
        when (item.acquisitionCondition) {
            null -> "무료 획득"
            AcquisitionCondition.REGISTERED_PLANT -> "식물 1개 등록"
        }
}

data class InventoryAcquireRequest(
    val accountId: AccountId,
    val itemId: ItemId,
    val expectedCatalogRevision: Revision,
    val operationId: OperationId,
)

data class InventoryOwnershipReceipt(
    val accountId: AccountId,
    val itemId: ItemId,
    val catalogRevision: Revision,
    val ownershipRevision: Revision,
    val acquiredAt: Instant,
    val mediaIdentity: CatalogMediaIdentity,
)

enum class InventoryOwnershipReceiptKind {
    ACQUIRED,
    ALREADY_OWNED,
}

@JvmInline value class InventoryReceiptId(val value: String)

data class InventoryAcquisitionTerminalReceipt(
    val owner: AccountId,
    val itemId: ItemId,
    val operationId: OperationId,
    val kind: InventoryOwnershipReceiptKind,
    val receipt: InventoryOwnershipReceipt,
    val createdAtEpochMillis: Long = receipt.acquiredAt.toEpochMilli(),
) {
    val receiptId = InventoryReceiptId("${owner.value}/${operationId.value}")

    init {
        require(receipt.accountId == owner && receipt.itemId == itemId)
        require(createdAtEpochMillis >= 0)
    }
}

data class InventoryReceiptClaimant(
    val presentationToken: String,
    val controllerEpoch: Long,
    val generation: Long,
) {
    init {
        require(presentationToken.isNotBlank() && presentationToken.length <= 128)
        require(controllerEpoch >= 1)
        require(generation >= 0)
    }
}

enum class InventoryReceiptDeliveryPhase {
    CLAIMED,
    PRESENTED,
    ACK_PENDING,
}

data class InventoryReceiptClaim(
    val receipt: InventoryAcquisitionTerminalReceipt,
    val claimant: InventoryReceiptClaimant,
    val rowVersion: Long,
    val leaseExpiresAtEpochMillis: Long,
    val deliveryPhase: InventoryReceiptDeliveryPhase = InventoryReceiptDeliveryPhase.CLAIMED,
) {
    init {
        require(rowVersion >= 1)
        require(leaseExpiresAtEpochMillis >= 0)
    }
}

data class InventoryFeedbackPresentationToken(
    val receiptId: InventoryReceiptId,
    val claimant: InventoryReceiptClaimant,
    val rowVersion: Long,
) {
    init {
        require(rowVersion >= 1)
    }
}

fun InventoryReceiptClaim.feedbackPresentationToken() =
    InventoryFeedbackPresentationToken(receipt.receiptId, claimant, rowVersion)

data class InventoryReceiptPresentationExpectation(
    val receipt: InventoryAcquisitionTerminalReceipt,
    val rowVersion: Long? = null,
) {
    init {
        rowVersion?.let { require(it >= 1) }
    }
}

fun InventoryReceiptClaim.presentationExpectation() =
    InventoryReceiptPresentationExpectation(receipt, rowVersion)

sealed interface InventoryReceiptClaimResult {
    data class Claimed(val claim: InventoryReceiptClaim) : InventoryReceiptClaimResult

    data class Unavailable(val retryAtEpochMillis: Long) : InventoryReceiptClaimResult {
        init {
            require(retryAtEpochMillis >= 0)
        }
    }

    data object Missing : InventoryReceiptClaimResult

    data object Stale : InventoryReceiptClaimResult

    data object Mismatch : InventoryReceiptClaimResult

    data object DatabaseFailure : InventoryReceiptClaimResult

    data object Forbidden : InventoryReceiptClaimResult
}

sealed interface InventoryReceiptPresentationResult {
    data class Presented(val claim: InventoryReceiptClaim) : InventoryReceiptPresentationResult

    data object Mismatch : InventoryReceiptPresentationResult

    data object Forbidden : InventoryReceiptPresentationResult

    data object DatabaseFailure : InventoryReceiptPresentationResult
}

sealed interface InventoryReceiptConsumptionResult {
    data class PendingAcknowledgement(val claim: InventoryReceiptClaim) :
        InventoryReceiptConsumptionResult

    data object Missing : InventoryReceiptConsumptionResult

    data object Stale : InventoryReceiptConsumptionResult

    data object Mismatch : InventoryReceiptConsumptionResult

    data object DatabaseFailure : InventoryReceiptConsumptionResult

    data object Forbidden : InventoryReceiptConsumptionResult
}

enum class InventoryReceiptAcknowledgement {
    ACKNOWLEDGED,
    ALREADY_ACKNOWLEDGED,
    MISSING,
    MISMATCH,
    DATABASE_FAILURE,
    FORBIDDEN,
}

sealed interface InventoryAcquireResult {
    data class Success(val receipt: InventoryOwnershipReceipt) : InventoryAcquireResult

    data class AlreadyOwned(val receipt: InventoryOwnershipReceipt) : InventoryAcquireResult

    data class ConditionNotMet(
        val itemId: ItemId,
        val catalogRevision: Revision,
        val condition: AcquisitionCondition,
    ) : InventoryAcquireResult

    data class Failure(
        val reason: InventoryFailure,
        val operationId: OperationId? = null,
    ) : InventoryAcquireResult

    data object Forbidden : InventoryAcquireResult
}

enum class InventoryFailure {
    NETWORK,
    CATALOG_CHANGED,
    ITEM_UNAVAILABLE,
    IDEMPOTENCY_MISMATCH,
    MALFORMED_RESPONSE,
    DATABASE,
}

sealed interface InventoryLoadResult {
    data class Ready(
        val snapshot: InventorySnapshot,
        val stale: Boolean,
        val receiptCandidates: List<InventoryReceiptId> = emptyList(),
        val receiptCandidatesAuthoritative: Boolean = true,
    ) : InventoryLoadResult

    data class Partial(
        val snapshot: InventorySnapshot,
        val stale: Boolean,
        val receiptCandidates: List<InventoryReceiptId> = emptyList(),
        val receiptCandidatesAuthoritative: Boolean = true,
    ) : InventoryLoadResult

    data object Forbidden : InventoryLoadResult

    data object Failed : InventoryLoadResult
}

interface InventoryRepository {
    suspend fun load(): InventoryLoadResult

    suspend fun acquire(request: InventoryAcquireRequest): InventoryAcquireResult

    suspend fun claimForPresentation(
        receiptId: InventoryReceiptId,
        expected: InventoryReceiptPresentationExpectation?,
        claimant: InventoryReceiptClaimant,
    ): InventoryReceiptClaimResult = InventoryReceiptClaimResult.Missing

    suspend fun markReceiptPresented(
        claim: InventoryReceiptClaim
    ): InventoryReceiptPresentationResult

    suspend fun markReceiptConsumed(
        claim: InventoryReceiptClaim
    ): InventoryReceiptConsumptionResult =
        InventoryReceiptConsumptionResult.PendingAcknowledgement(claim)

    suspend fun acknowledgeReceipt(claim: InventoryReceiptClaim): InventoryReceiptAcknowledgement =
        InventoryReceiptAcknowledgement.MISSING
}

sealed interface InventoryAuthOwnership {
    data object Restoring : InventoryAuthOwnership

    data object Unknown : InventoryAuthOwnership

    data object SignedOut : InventoryAuthOwnership

    data object Unmanaged : InventoryAuthOwnership

    data class Authenticated(val accountId: AccountId) : InventoryAuthOwnership
}
