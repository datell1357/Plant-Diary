package com.planterior.helper.core.data

import com.planterior.helper.core.database.AuthoritativeInventoryCacheWrite
import com.planterior.helper.core.database.CacheDao
import com.planterior.helper.core.database.CachedInventoryState
import com.planterior.helper.core.database.CachedOwnedItemEntity
import com.planterior.helper.core.database.CachedShopItemEntity
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision

/** The one persistence mapping used by every surface that consumes authoritative inventory. */
fun AuthoritativeInventory.cacheWrite(
    snapshotToken: String? = null,
    snapshotGeneration: Long? = null,
): AuthoritativeInventoryCacheWrite =
    AuthoritativeInventoryCacheWrite(
        accountId = accountId.value,
        generation = generation,
        snapshotHash = snapshotHash,
        registeredPlantCount = registeredPlantCount,
        loadedAtEpochMillis = loadedAtEpochMillis,
        partial = partial,
        catalog = catalog.map { it.cached(accountId) },
        owned = owned.map { it.cached(accountId) },
        snapshotToken = snapshotToken,
        snapshotGeneration = snapshotGeneration,
    )

/**
 * Reconstructs and content-verifies an exact authoritative snapshot. Any missing, torn, legacy, or
 * mutated field fails closed instead of manufacturing catalog identity.
 */
fun CachedInventoryState.verifiedAuthoritativeInventoryOrNull(
    expectedOwner: AccountId
): AuthoritativeInventory? = runCatching {
    require(watermark.accountId == expectedOwner.value && watermark.verified)
    require(watermark.generation >= 1)
    require(watermark.snapshotHash.matches(Regex("^[a-f0-9]{64}$")))
    require(watermark.registeredPlantCount in 0..200)
    require(watermark.loadedAtEpochMillis in 0..MAX_SAFE_INTEGER)
    require(catalog.size <= 200 && owned.size <= 200)
    val authoritativeCatalog = catalog.map { it.authoritative(expectedOwner) }
    val authoritativeOwned = owned.map { it.authoritative(expectedOwner) }
    require(authoritativeCatalog.map { it.itemId }.distinct().size == authoritativeCatalog.size)
    require(authoritativeOwned.map { it.itemId }.distinct().size == authoritativeOwned.size)
    val catalogIds = authoritativeCatalog.mapTo(mutableSetOf()) { it.itemId }
    authoritativeOwned.forEach {
        require(
            (it.availability == AuthoritativeInventoryAvailability.AVAILABLE) ==
                (it.itemId in catalogIds)
        )
    }
    require(
        watermark.partial ||
            authoritativeOwned.none {
                it.availability == AuthoritativeInventoryAvailability.UNAVAILABLE
            }
    )
    val expectedHash =
        authoritativeInventorySnapshotHash(
            expectedOwner,
            authoritativeCatalog,
            authoritativeOwned,
            watermark.registeredPlantCount,
            watermark.partial,
        )
    require(expectedHash == watermark.snapshotHash)
    AuthoritativeInventory(
        contractVersion = INVENTORY_CONTRACT_VERSION,
        accountId = expectedOwner,
        catalog = authoritativeCatalog,
        owned = authoritativeOwned,
        registeredPlantCount = watermark.registeredPlantCount,
        loadedAtEpochMillis = watermark.loadedAtEpochMillis,
        partial = watermark.partial,
        generation = watermark.generation,
        snapshotHash = watermark.snapshotHash,
    )
}
    .getOrNull()

/**
 * Reads verified cache state and atomically purges the exact corrupt generation if validation
 * fails.
 */
suspend fun CacheDao.verifiedAuthoritativeInventory(accountId: AccountId): AuthoritativeInventory? {
    val cached = currentInventoryCache(accountId.value) ?: return null
    cached.verifiedAuthoritativeInventoryOrNull(accountId)?.let {
        return it
    }
    purgeInventoryCacheIfMatches(
        accountId.value,
        cached.watermark.generation,
        cached.watermark.snapshotHash,
    )
    return null
}

private fun AuthoritativeCatalogItem.cached(owner: AccountId) =
    CachedShopItemEntity(
        accountId = owner.value,
        itemId = itemId.value,
        name = name,
        description = description,
        category = category.name,
        assetPath = mediaIdentity.path,
        acquisitionCondition = acquisitionCondition?.wireValue,
        revision = revision.value,
        updatedAtEpochMillis = updatedAtEpochMillis,
        assetSha256 = mediaIdentity.sha256,
        assetByteSize = mediaIdentity.byteSize,
        assetMimeType = mediaIdentity.mimeType,
        assetWidth = mediaIdentity.width,
        assetHeight = mediaIdentity.height,
        assetMediaRevision = mediaIdentity.mediaRevision.value,
    )

private fun AuthoritativeOwnedItem.cached(owner: AccountId) =
    CachedOwnedItemEntity(
        accountId = owner.value,
        itemId = itemId.value,
        acquiredAtEpochMillis = acquiredAtEpochMillis,
        applied = applied,
        revision = revision.value,
        availability = availability.name,
        nameSnapshot = catalogSnapshot?.name,
        categorySnapshot = catalogSnapshot?.category?.name,
        assetPathSnapshot = catalogSnapshot?.mediaIdentity?.path,
        catalogRevisionSnapshot = catalogSnapshot?.catalogRevision?.value,
        assetSha256Snapshot = catalogSnapshot?.mediaIdentity?.sha256,
        assetByteSizeSnapshot = catalogSnapshot?.mediaIdentity?.byteSize,
        assetMimeTypeSnapshot = catalogSnapshot?.mediaIdentity?.mimeType,
        assetWidthSnapshot = catalogSnapshot?.mediaIdentity?.width,
        assetHeightSnapshot = catalogSnapshot?.mediaIdentity?.height,
        assetMediaRevisionSnapshot = catalogSnapshot?.mediaIdentity?.mediaRevision?.value,
    )

private fun CachedShopItemEntity.authoritative(owner: AccountId): AuthoritativeCatalogItem {
    require(accountId == owner.value)
    require(name.codePointCount(0, name.length) in 1..100)
    require(description.codePointCount(0, description.length) in 1..500)
    val typedItemId = ItemId(itemId)
    val identity =
        CatalogMediaIdentity(
            assetPath,
            assetSha256,
            assetByteSize,
            assetMimeType,
            assetWidth,
            assetHeight,
            Revision(assetMediaRevision),
        )
    require(identity.path.startsWith("catalog-assets/${typedItemId.value}/"))
    return AuthoritativeCatalogItem(
        itemId = typedItemId,
        name = name,
        description = description,
        category = ItemCategory.valueOf(category),
        mediaIdentity = identity,
        acquisitionCondition =
            acquisitionCondition?.let { value ->
                AuthoritativeInventoryCondition.entries.single { it.wireValue == value }
            },
        revision = Revision(revision).also { require(it.value >= 1) },
        updatedAtEpochMillis = updatedAtEpochMillis.also { require(it in 0..MAX_SAFE_INTEGER) },
    )
}

private fun CachedOwnedItemEntity.authoritative(owner: AccountId): AuthoritativeOwnedItem {
    require(accountId == owner.value)
    val snapshotValues =
        listOf(
            nameSnapshot,
            categorySnapshot,
            assetPathSnapshot,
            catalogRevisionSnapshot,
            assetSha256Snapshot,
            assetByteSizeSnapshot,
            assetMimeTypeSnapshot,
            assetWidthSnapshot,
            assetHeightSnapshot,
            assetMediaRevisionSnapshot,
        )
    require(snapshotValues.all { it == null } || snapshotValues.none { it == null })
    val snapshot =
        if (snapshotValues.all { it == null }) {
            null
        } else {
            val snapshotName = requireNotNull(nameSnapshot)
            require(snapshotName.codePointCount(0, snapshotName.length) in 1..100)
            val identity =
                CatalogMediaIdentity(
                    requireNotNull(assetPathSnapshot),
                    requireNotNull(assetSha256Snapshot),
                    requireNotNull(assetByteSizeSnapshot),
                    requireNotNull(assetMimeTypeSnapshot),
                    requireNotNull(assetWidthSnapshot),
                    requireNotNull(assetHeightSnapshot),
                    Revision(requireNotNull(assetMediaRevisionSnapshot)),
                )
            require(identity.path.startsWith("catalog-assets/$itemId/"))
            AuthoritativeOwnedCatalogSnapshot(
                name = snapshotName,
                category = ItemCategory.valueOf(requireNotNull(categorySnapshot)),
                mediaIdentity = identity,
                catalogRevision =
                    Revision(requireNotNull(catalogRevisionSnapshot)).also {
                        require(it.value >= 1)
                    },
            )
        }
    return AuthoritativeOwnedItem(
        itemId = ItemId(itemId),
        acquiredAtEpochMillis = acquiredAtEpochMillis.also { require(it in 0..MAX_SAFE_INTEGER) },
        applied = applied,
        revision = Revision(revision).also { require(it.value >= 1) },
        availability = AuthoritativeInventoryAvailability.valueOf(availability),
        catalogSnapshot = snapshot,
    )
}

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
