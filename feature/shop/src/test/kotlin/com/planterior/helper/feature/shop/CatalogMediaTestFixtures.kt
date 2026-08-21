package com.planterior.helper.feature.shop

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.security.MessageDigest
import java.time.Instant

internal fun testCatalogMediaIdentity(
    itemId: String,
    source: String = itemId,
    mediaRevision: Long = 1,
): CatalogMediaIdentity {
    val digest =
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    return CatalogMediaIdentity(
        path = "catalog-assets/$itemId/$digest.webp",
        sha256 = digest,
        byteSize = 4,
        mimeType = "image/webp",
        width = 1,
        height = 1,
        mediaRevision = Revision(mediaRevision),
    )
}

@Suppress("FunctionName")
internal fun InventoryItem(
    id: ItemId,
    name: String,
    description: String,
    category: ItemCategory,
    legacyAssetPath: String,
    acquisitionCondition: AcquisitionCondition?,
    revision: Revision,
    updatedAt: Instant,
): InventoryItem =
    InventoryItem(
        id,
        name,
        description,
        category,
        testCatalogMediaIdentity(id.value, legacyAssetPath),
        acquisitionCondition,
        revision,
        updatedAt,
    )

@Suppress("FunctionName")
internal fun OwnedCatalogSnapshot(
    name: String,
    category: ItemCategory,
    legacyAssetPath: String,
    catalogRevision: Revision,
): OwnedCatalogSnapshot {
    val itemId = legacyAssetPath.split('/').getOrNull(1) ?: "legacy"
    return OwnedCatalogSnapshot(
        name,
        category,
        testCatalogMediaIdentity(itemId, legacyAssetPath),
        catalogRevision,
    )
}

@Suppress("FunctionName")
internal fun InventoryOwnershipReceipt(
    accountId: AccountId,
    itemId: ItemId,
    catalogRevision: Revision,
    ownershipRevision: Revision,
    acquiredAt: Instant,
): InventoryOwnershipReceipt =
    InventoryOwnershipReceipt(
        accountId,
        itemId,
        catalogRevision,
        ownershipRevision,
        acquiredAt,
        testCatalogMediaIdentity(itemId.value, "receipt:${itemId.value}"),
    )
