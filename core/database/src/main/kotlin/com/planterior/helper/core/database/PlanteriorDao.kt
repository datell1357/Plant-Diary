package com.planterior.helper.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlant(entity: CachedPlantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(entity: CachedWateringScheduleEntity)

    @Query(
        "SELECT * FROM cached_plants WHERE accountId = :accountId ORDER BY updatedAtEpochMillis DESC, plantId ASC"
    )
    fun observePlants(accountId: String): Flow<List<CachedPlantEntity>>

    @Query(
        "SELECT * FROM cached_plants WHERE accountId = :accountId ORDER BY updatedAtEpochMillis DESC, plantId ASC"
    )
    suspend fun plants(accountId: String): List<CachedPlantEntity>

    @Query("SELECT * FROM cached_plants WHERE accountId = :accountId AND plantId = :plantId")
    suspend fun plant(accountId: String, plantId: String): CachedPlantEntity?

    @Query(
        "SELECT * FROM cached_watering_schedules WHERE accountId = :accountId AND scheduleId = :scheduleId"
    )
    suspend fun schedule(accountId: String, scheduleId: String): CachedWateringScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMiniHome(entity: CachedMiniHomeEntity)

    @Query("SELECT * FROM cached_mini_homes WHERE accountId = :accountId")
    suspend fun miniHome(accountId: String): CachedMiniHomeEntity?

    @Query("DELETE FROM cached_mini_homes WHERE accountId = :accountId")
    suspend fun clearMiniHome(accountId: String)

    @Query(
        "UPDATE cached_mini_homes SET name = :canonicalName WHERE accountId = :accountId AND miniHomeId = :miniHomeId AND revision = :revision AND name = :legacyName"
    )
    suspend fun rewriteLegacyMiniHomeName(
        accountId: String,
        miniHomeId: String,
        revision: Long,
        legacyName: String,
        canonicalName: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMiniHomePlacements(entities: List<CachedMiniHomePlacementEntity>)

    @Query(
        "SELECT * FROM cached_mini_home_placements WHERE accountId = :accountId AND miniHomeId = :miniHomeId AND layoutRevision = :layoutRevision ORDER BY zIndex ASC, placementId ASC"
    )
    suspend fun miniHomePlacements(
        accountId: String,
        miniHomeId: String,
        layoutRevision: Long,
    ): List<CachedMiniHomePlacementEntity>

    @Query("DELETE FROM cached_mini_home_placements WHERE accountId = :accountId")
    suspend fun clearMiniHomePlacements(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMiniHomeCacheWatermark(entity: MiniHomeCacheWatermarkEntity)

    @Query("SELECT * FROM mini_home_cache_watermarks WHERE accountId = :accountId")
    suspend fun miniHomeCacheWatermark(accountId: String): MiniHomeCacheWatermarkEntity?

    @Query("DELETE FROM mini_home_cache_watermarks WHERE accountId = :accountId")
    suspend fun clearMiniHomeCacheWatermark(accountId: String)

    @Transaction
    suspend fun currentMiniHomeCache(accountId: String): CachedMiniHomeLayoutState? {
        val home = miniHome(accountId)
        val persisted = miniHomeCacheWatermark(accountId)
        if (home == null && persisted == null) return null
        val watermark =
            persisted?.watermark()
                ?: MiniHomeCacheWatermark(
                    accountId = accountId,
                    generation = requireNotNull(home).revision,
                    kind = MiniHomeCacheWatermarkKind.PRESENT,
                    layoutRevision = home.revision,
                    miniHomeId = home.miniHomeId,
                    operationId = null,
                    payloadHash = null,
                    tombstoneId = null,
                    authoritativeAtEpochMillis = home.updatedAtEpochMillis,
                    verified = false,
                )
        val placements =
            home?.let { miniHomePlacements(accountId, it.miniHomeId, it.revision) }.orEmpty()
        return CachedMiniHomeLayoutState(watermark, home, placements)
    }

    /**
     * Applies one complete authoritative owner state without allowing delayed readers to regress or
     * resurrect the cache.
     */
    @Transaction
    suspend fun applyAuthoritativeMiniHome(
        write: AuthoritativeMiniHomeCacheWrite
    ): MiniHomeCacheApplyResult {
        validateMiniHomeCacheWrite(write)
        val before = currentMiniHomeCache(write.accountId)
        if (before?.watermark?.verified == true) {
            if (write.generation < before.watermark.generation) {
                return MiniHomeCacheApplyResult.Ignored(before)
            }
            if (write.generation == before.watermark.generation) {
                val candidate = write.watermark()
                if (!before.watermark.sameDomainIdentity(candidate) || !before.sameContent(write)) {
                    return MiniHomeCacheApplyResult.Conflict(before)
                }
                return when (
                    coherenceUpdate(
                        before.watermark.snapshotToken,
                        before.watermark.snapshotGeneration,
                        candidate.snapshotToken,
                        candidate.snapshotGeneration,
                    )
                ) {
                    CoherenceUpdate.APPLY -> {
                        upsertMiniHomeCacheWatermark(candidate.entity())
                        MiniHomeCacheApplyResult.Applied(
                            requireNotNull(currentMiniHomeCache(write.accountId))
                        )
                    }
                    CoherenceUpdate.IGNORE -> MiniHomeCacheApplyResult.Ignored(before)
                    CoherenceUpdate.CONFLICT -> MiniHomeCacheApplyResult.Conflict(before)
                }
            }
            if (
                before.watermark.kind == MiniHomeCacheWatermarkKind.PRESENT &&
                    write is AuthoritativeMiniHomeCacheWrite.Layout
            ) {
                val currentRevision = requireNotNull(before.home).revision
                if (write.home.revision < currentRevision) {
                    return MiniHomeCacheApplyResult.Ignored(before)
                }
            }
        }

        clearMiniHomePlacements(write.accountId)
        clearMiniHome(write.accountId)
        when (write) {
            is AuthoritativeMiniHomeCacheWrite.Layout -> {
                upsertMiniHome(write.home)
                if (write.placements.isNotEmpty()) upsertMiniHomePlacements(write.placements)
            }
            is AuthoritativeMiniHomeCacheWrite.Deletion -> Unit
        }
        upsertMiniHomeCacheWatermark(write.watermark().entity())
        return MiniHomeCacheApplyResult.Applied(
            requireNotNull(currentMiniHomeCache(write.accountId))
        )
    }

    /** 복구할 수 없는 legacy 이름과 그 revision의 배치를 한 캐시 경계에서 격리한다. */
    @Transaction
    suspend fun quarantineMiniHome(accountId: String) {
        clearMiniHomePlacements(accountId)
        clearMiniHome(accountId)
    }

    @Query("DELETE FROM cached_plants WHERE accountId = :accountId")
    suspend fun clearPlants(accountId: String)

    @Query("DELETE FROM cached_watering_schedules WHERE accountId = :accountId")
    suspend fun clearSchedules(accountId: String)

    @Query("SELECT plantId FROM cached_plants WHERE accountId = :accountId")
    suspend fun plantIds(accountId: String): List<String>

    @Query("SELECT scheduleId FROM cached_watering_schedules WHERE accountId = :accountId")
    suspend fun scheduleIds(accountId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShopItems(entities: List<CachedShopItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOwnedItems(entities: List<CachedOwnedItemEntity>)

    @Query(
        "SELECT * FROM cached_shop_items WHERE accountId = :accountId ORDER BY category ASC, name ASC, itemId ASC"
    )
    suspend fun shopItems(accountId: String): List<CachedShopItemEntity>

    @Query(
        "SELECT * FROM cached_owned_items WHERE accountId = :accountId ORDER BY acquiredAtEpochMillis DESC, itemId ASC"
    )
    suspend fun ownedItems(accountId: String): List<CachedOwnedItemEntity>

    @Query("DELETE FROM cached_shop_items WHERE accountId = :accountId")
    suspend fun clearShopItems(accountId: String)

    @Query("DELETE FROM cached_owned_items WHERE accountId = :accountId")
    suspend fun clearOwnedItems(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInventorySnapshotWatermark(entity: InventorySnapshotWatermarkEntity)

    @Query("SELECT * FROM inventory_snapshot_watermarks WHERE accountId = :accountId")
    suspend fun inventorySnapshotWatermark(accountId: String): InventorySnapshotWatermarkEntity?

    @Query("DELETE FROM inventory_snapshot_watermarks WHERE accountId = :accountId")
    suspend fun clearInventorySnapshotWatermark(accountId: String)

    @Transaction
    suspend fun purgeInventoryCacheIfMatches(
        accountId: String,
        generation: Long,
        snapshotHash: String,
    ): Boolean {
        val watermark = inventorySnapshotWatermark(accountId) ?: return false
        if (watermark.generation != generation || watermark.snapshotHash != snapshotHash) {
            return false
        }
        clearShopItems(accountId)
        clearOwnedItems(accountId)
        clearInventorySnapshotWatermark(accountId)
        return true
    }

    @Transaction
    suspend fun currentInventoryCache(accountId: String): CachedInventoryState? {
        val watermark = inventorySnapshotWatermark(accountId) ?: return null
        return CachedInventoryState(watermark, shopItems(accountId), ownedItems(accountId))
    }

    @Transaction
    suspend fun currentMiniHomeSnapshotCache(accountId: String): CachedMiniHomeSnapshotState? {
        val layout = currentMiniHomeCache(accountId)
        val inventory = currentInventoryCache(accountId)
        if (layout == null && inventory == null) return null
        val layoutWatermark = layout?.watermark
        val inventoryWatermark = inventory?.watermark
        val coherent =
            layoutWatermark?.verified == true &&
                inventoryWatermark?.verified == true &&
                layoutWatermark.snapshotToken != null &&
                layoutWatermark.snapshotGeneration != null &&
                layoutWatermark.snapshotToken == inventoryWatermark.snapshotToken &&
                layoutWatermark.snapshotGeneration == inventoryWatermark.snapshotGeneration
        return CachedMiniHomeSnapshotState(layout, inventory, coherent)
    }

    @Transaction
    suspend fun purgeIncoherentMiniHomeSnapshot(accountId: String): Boolean {
        val current = currentMiniHomeSnapshotCache(accountId) ?: return false
        if (current.coherent) return false
        clearMiniHomePlacements(accountId)
        clearMiniHome(accountId)
        clearMiniHomeCacheWatermark(accountId)
        clearShopItems(accountId)
        clearOwnedItems(accountId)
        clearInventorySnapshotWatermark(accountId)
        return true
    }

    /**
     * Atomically rejects stale or conflicting complete snapshots before replacing owner cache rows.
     */
    @Transaction
    suspend fun applyAuthoritativeInventory(
        write: AuthoritativeInventoryCacheWrite
    ): InventoryCacheApplyResult {
        validateInventoryCacheWrite(write)
        val before = currentInventoryCache(write.accountId)
        if (before?.watermark?.verified == true) {
            if (write.generation < before.watermark.generation) {
                return InventoryCacheApplyResult.Ignored(before)
            }
            if (write.generation == before.watermark.generation) {
                val sameIdentity =
                    write.snapshotHash == before.watermark.snapshotHash &&
                        write.registeredPlantCount == before.watermark.registeredPlantCount &&
                        write.partial == before.watermark.partial
                val sameContent =
                    write.catalog.sortedBy { it.itemId } == before.catalog.sortedBy { it.itemId } &&
                        write.owned.sortedBy { it.itemId } == before.owned.sortedBy { it.itemId }
                if (!sameIdentity || !sameContent) {
                    return InventoryCacheApplyResult.Conflict(before)
                }
                return when (
                    coherenceUpdate(
                        before.watermark.snapshotToken,
                        before.watermark.snapshotGeneration,
                        write.snapshotToken,
                        write.snapshotGeneration,
                    )
                ) {
                    CoherenceUpdate.APPLY -> {
                        upsertInventorySnapshotWatermark(
                            before.watermark.copy(
                                snapshotToken = write.snapshotToken,
                                snapshotGeneration = write.snapshotGeneration,
                            )
                        )
                        InventoryCacheApplyResult.Applied(
                            requireNotNull(currentInventoryCache(write.accountId))
                        )
                    }
                    CoherenceUpdate.IGNORE -> InventoryCacheApplyResult.Ignored(before)
                    CoherenceUpdate.CONFLICT -> InventoryCacheApplyResult.Conflict(before)
                }
            }
        }

        clearShopItems(write.accountId)
        clearOwnedItems(write.accountId)
        if (write.catalog.isNotEmpty()) upsertShopItems(write.catalog)
        if (write.owned.isNotEmpty()) upsertOwnedItems(write.owned)
        upsertInventorySnapshotWatermark(
            InventorySnapshotWatermarkEntity(
                accountId = write.accountId,
                generation = write.generation,
                snapshotHash = write.snapshotHash,
                registeredPlantCount = write.registeredPlantCount,
                loadedAtEpochMillis = write.loadedAtEpochMillis,
                partial = write.partial,
                verified = true,
                snapshotToken = write.snapshotToken,
                snapshotGeneration = write.snapshotGeneration,
            )
        )
        return InventoryCacheApplyResult.Applied(
            requireNotNull(currentInventoryCache(write.accountId))
        )
    }

    @Transaction
    suspend fun replaceInventorySnapshot(
        accountId: String,
        catalog: List<CachedShopItemEntity>,
        owned: List<CachedOwnedItemEntity>,
    ) {
        require(catalog.all { it.accountId == accountId })
        require(owned.all { it.accountId == accountId })
        clearShopItems(accountId)
        clearOwnedItems(accountId)
        if (catalog.isNotEmpty()) upsertShopItems(catalog)
        if (owned.isNotEmpty()) upsertOwnedItems(owned)
    }

    @Query(
        "DELETE FROM cached_plants WHERE accountId = :accountId AND plantId = :plantId AND NOT EXISTS (SELECT 1 FROM operation_outbox WHERE accountId = :accountId AND aggregateType IN ('personalPlant', 'personalPlants') AND aggregateId = :plantId AND state IN ('PENDING', 'CONFLICT', 'FAILED'))"
    )
    suspend fun deleteRemoteMissingPlantUnlessDraft(accountId: String, plantId: String)

    @Query(
        "DELETE FROM cached_watering_schedules WHERE accountId = :accountId AND scheduleId = :scheduleId AND NOT EXISTS (SELECT 1 FROM operation_outbox WHERE accountId = :accountId AND aggregateType = 'wateringSchedule' AND aggregateId = :scheduleId AND state IN ('PENDING', 'CONFLICT', 'FAILED'))"
    )
    suspend fun deleteRemoteMissingScheduleUnlessDraft(accountId: String, scheduleId: String)

    @Transaction
    suspend fun reconcilePlants(accountId: String, remote: List<CachedPlantEntity>) {
        remote.forEach { upsertPlant(it) }
        val remoteIds = remote.mapTo(mutableSetOf()) { it.plantId }
        plantIds(accountId).filterNot(remoteIds::contains).forEach {
            deleteRemoteMissingPlantUnlessDraft(accountId, it)
        }
    }

    @Transaction
    suspend fun reconcileSchedules(
        accountId: String,
        remote: List<CachedWateringScheduleEntity>,
    ) {
        remote.forEach { upsertSchedule(it) }
        val remoteIds = remote.mapTo(mutableSetOf()) { it.scheduleId }
        scheduleIds(accountId).filterNot(remoteIds::contains).forEach {
            deleteRemoteMissingScheduleUnlessDraft(accountId, it)
        }
    }

    @Transaction
    suspend fun clearVisibleAccount(accountId: String) {
        clearPlants(accountId)
        clearSchedules(accountId)
        clearMiniHomePlacements(accountId)
        clearMiniHome(accountId)
        clearMiniHomeCacheWatermark(accountId)
        clearShopItems(accountId)
        clearOwnedItems(accountId)
        clearInventorySnapshotWatermark(accountId)
    }
}

@Dao
interface TerminalAccountDeletionDao {
    @Query("DELETE FROM cached_plants WHERE accountId = :accountId")
    suspend fun purgePlants(accountId: String)

    @Query("DELETE FROM cached_watering_schedules WHERE accountId = :accountId")
    suspend fun purgeWateringSchedules(accountId: String)

    @Query("DELETE FROM operation_outbox WHERE accountId = :accountId")
    suspend fun purgeOperationOutbox(accountId: String)

    @Query("DELETE FROM cached_mini_homes WHERE accountId = :accountId")
    suspend fun purgeMiniHomes(accountId: String)

    @Query("DELETE FROM cached_mini_home_placements WHERE accountId = :accountId")
    suspend fun purgeMiniHomePlacements(accountId: String)

    @Query("DELETE FROM mini_home_cache_watermarks WHERE accountId = :accountId")
    suspend fun purgeMiniHomeWatermarks(accountId: String)

    @Query("DELETE FROM cached_shop_items WHERE accountId = :accountId")
    suspend fun purgeShopItems(accountId: String)

    @Query("DELETE FROM cached_owned_items WHERE accountId = :accountId")
    suspend fun purgeOwnedItems(accountId: String)

    @Query("DELETE FROM inventory_snapshot_watermarks WHERE accountId = :accountId")
    suspend fun purgeInventoryWatermarks(accountId: String)

    @Query("DELETE FROM inventory_acquisition_operations WHERE accountId = :accountId")
    suspend fun purgeInventoryOperations(accountId: String)

    @Query("DELETE FROM last_sync WHERE accountId = :accountId")
    suspend fun purgeLastSync(accountId: String)

    @Transaction
    suspend fun purgeOwner(accountId: String) {
        purgePlants(accountId)
        purgeWateringSchedules(accountId)
        purgeOperationOutbox(accountId)
        purgeMiniHomes(accountId)
        purgeMiniHomePlacements(accountId)
        purgeMiniHomeWatermarks(accountId)
        purgeShopItems(accountId)
        purgeOwnedItems(accountId)
        purgeInventoryWatermarks(accountId)
        purgeInventoryOperations(accountId)
        purgeLastSync(accountId)
    }
}

private fun validateInventoryCacheWrite(write: AuthoritativeInventoryCacheWrite) {
    require(write.accountId.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
    require(write.generation >= 1)
    require(write.snapshotHash.matches(Regex("^[a-f0-9]{64}$")))
    require(write.registeredPlantCount in 0..200)
    require(write.catalog.size <= 200 && write.owned.size <= 200)
    require(write.catalog.all { it.accountId == write.accountId })
    require(write.owned.all { it.accountId == write.accountId })
    require(write.catalog.map { it.itemId }.distinct().size == write.catalog.size)
    require(write.owned.map { it.itemId }.distinct().size == write.owned.size)
    validateSnapshotIdentity(write.snapshotToken, write.snapshotGeneration)
}

private fun validateSnapshotIdentity(token: String?, generation: Long?) {
    require((token == null) == (generation == null))
    token?.let { require(it.matches(Regex("^[a-f0-9]{64}$"))) }
    generation?.let { require(it >= 1) }
}

@Dao
interface InventoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(entity: InventoryAcquisitionOperationEntity): Long

    @Query(
        "SELECT * FROM inventory_acquisition_operations WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun operation(
        accountId: String,
        operationId: String,
    ): InventoryAcquisitionOperationEntity?

    @Query(
        "SELECT * FROM inventory_acquisition_operations WHERE accountId = :accountId AND state = 'PENDING' ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun pendingOperations(accountId: String): List<InventoryAcquisitionOperationEntity>

    @Query(
        "SELECT * FROM inventory_acquisition_operations WHERE accountId = :accountId AND state = 'COMPLETED' AND feedbackDeliveryState IN ('UNDELIVERED', 'CLAIMED', 'PRESENTED', 'ACK_PENDING') ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun receiptsAwaitingDelivery(
        accountId: String
    ): List<InventoryAcquisitionOperationEntity>

    @Query(
        "UPDATE inventory_acquisition_operations SET state = 'COMPLETED', result = :result, lastErrorCode = NULL, feedbackDeliveryState = :feedbackDeliveryState, feedbackAcknowledgedAtEpochMillis = NULL, feedbackClaimToken = NULL, feedbackClaimControllerEpoch = NULL, feedbackClaimGeneration = NULL, feedbackClaimLeaseExpiresAtEpochMillis = NULL, feedbackRowVersion = feedbackRowVersion + 1 WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND expectedCatalogRevision = :expectedCatalogRevision AND requestHash = :requestHash AND state = 'PENDING'"
    )
    suspend fun completeOperation(
        accountId: String,
        operationId: String,
        itemId: String,
        expectedCatalogRevision: Long,
        requestHash: String,
        result: String,
        feedbackDeliveryState: String,
    ): Int

    @Query(
        "UPDATE inventory_acquisition_operations SET state = 'FAILED', result = NULL, lastErrorCode = :lastErrorCode, feedbackDeliveryState = 'NONE', feedbackAcknowledgedAtEpochMillis = NULL, feedbackClaimToken = NULL, feedbackClaimControllerEpoch = NULL, feedbackClaimGeneration = NULL, feedbackClaimLeaseExpiresAtEpochMillis = NULL, feedbackRowVersion = feedbackRowVersion + 1 WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND expectedCatalogRevision = :expectedCatalogRevision AND requestHash = :requestHash AND state = 'PENDING'"
    )
    suspend fun failOperation(
        accountId: String,
        operationId: String,
        itemId: String,
        expectedCatalogRevision: Long,
        requestHash: String,
        lastErrorCode: String,
    ): Int

    @Query(
        "UPDATE inventory_acquisition_operations SET feedbackDeliveryState = 'CLAIMED', feedbackClaimToken = :claimToken, feedbackClaimControllerEpoch = :controllerEpoch, feedbackClaimGeneration = :claimGeneration, feedbackClaimLeaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis, feedbackRowVersion = feedbackRowVersion + 1 WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND state = 'COMPLETED' AND result = :result AND feedbackRowVersion = :expectedRowVersion AND (feedbackDeliveryState = 'UNDELIVERED' OR (feedbackDeliveryState = 'CLAIMED' AND (feedbackClaimLeaseExpiresAtEpochMillis <= :nowEpochMillis OR (feedbackClaimToken = :claimToken AND (feedbackClaimControllerEpoch < :controllerEpoch OR (feedbackClaimControllerEpoch = :controllerEpoch AND feedbackClaimGeneration < :claimGeneration))))))"
    )
    suspend fun claimCompletedReceipt(
        accountId: String,
        operationId: String,
        itemId: String,
        result: String,
        expectedRowVersion: Long,
        claimToken: String,
        controllerEpoch: Long,
        claimGeneration: Long,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): Int

    @Query(
        "UPDATE inventory_acquisition_operations SET feedbackClaimToken = :claimToken, feedbackClaimControllerEpoch = :controllerEpoch, feedbackClaimGeneration = :claimGeneration, feedbackClaimLeaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis, feedbackRowVersion = feedbackRowVersion + 1 WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND state = 'COMPLETED' AND result = :result AND feedbackDeliveryState IN ('PRESENTED', 'ACK_PENDING') AND feedbackRowVersion = :expectedRowVersion AND (feedbackDeliveryState = 'ACK_PENDING' OR feedbackClaimLeaseExpiresAtEpochMillis <= :nowEpochMillis OR (feedbackClaimToken = :claimToken AND (feedbackClaimControllerEpoch < :controllerEpoch OR (feedbackClaimControllerEpoch = :controllerEpoch AND feedbackClaimGeneration < :claimGeneration))))"
    )
    suspend fun rebindPresentedOrPendingReceipt(
        accountId: String,
        operationId: String,
        itemId: String,
        result: String,
        expectedRowVersion: Long,
        claimToken: String,
        controllerEpoch: Long,
        claimGeneration: Long,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): Int

    @Query(
        "UPDATE inventory_acquisition_operations SET feedbackDeliveryState = 'PRESENTED' WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND state = 'COMPLETED' AND result = :result AND feedbackDeliveryState = 'CLAIMED' AND feedbackClaimToken = :claimToken AND feedbackClaimControllerEpoch = :controllerEpoch AND feedbackClaimGeneration = :claimGeneration AND feedbackRowVersion = :expectedRowVersion"
    )
    suspend fun markClaimedReceiptPresented(
        accountId: String,
        operationId: String,
        itemId: String,
        result: String,
        claimToken: String,
        controllerEpoch: Long,
        claimGeneration: Long,
        expectedRowVersion: Long,
    ): Int

    @Query(
        "UPDATE inventory_acquisition_operations SET feedbackDeliveryState = 'ACK_PENDING' WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND state = 'COMPLETED' AND result = :result AND feedbackDeliveryState = 'PRESENTED' AND feedbackClaimToken = :claimToken AND feedbackClaimControllerEpoch = :controllerEpoch AND feedbackClaimGeneration = :claimGeneration AND feedbackRowVersion = :expectedRowVersion"
    )
    suspend fun markPresentedReceiptConsumed(
        accountId: String,
        operationId: String,
        itemId: String,
        result: String,
        claimToken: String,
        controllerEpoch: Long,
        claimGeneration: Long,
        expectedRowVersion: Long,
    ): Int

    @Query(
        "UPDATE inventory_acquisition_operations SET feedbackDeliveryState = 'ACKNOWLEDGED', feedbackAcknowledgedAtEpochMillis = :acknowledgedAtEpochMillis, feedbackRowVersion = feedbackRowVersion + 1 WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND state = 'COMPLETED' AND result = :result AND feedbackDeliveryState = 'ACK_PENDING' AND feedbackClaimToken = :claimToken AND feedbackClaimControllerEpoch = :controllerEpoch AND feedbackClaimGeneration = :claimGeneration AND feedbackRowVersion = :expectedRowVersion"
    )
    suspend fun acknowledgePendingReceipt(
        accountId: String,
        operationId: String,
        itemId: String,
        result: String,
        claimToken: String,
        controllerEpoch: Long,
        claimGeneration: Long,
        expectedRowVersion: Long,
        acknowledgedAtEpochMillis: Long,
    ): Int

    @Query(
        "DELETE FROM inventory_acquisition_operations WHERE accountId = :accountId AND state = 'COMPLETED' AND feedbackDeliveryState = 'ACKNOWLEDGED' AND feedbackAcknowledgedAtEpochMillis IS NOT NULL AND feedbackAcknowledgedAtEpochMillis < :olderThanEpochMillis"
    )
    suspend fun deleteAcknowledgedCompletedOperations(
        accountId: String,
        olderThanEpochMillis: Long,
    ): Int

    @Query(
        "DELETE FROM inventory_acquisition_operations WHERE accountId = :accountId AND operationId = :operationId AND itemId = :itemId AND expectedCatalogRevision = :expectedCatalogRevision AND requestHash = :requestHash"
    )
    suspend fun deleteOperation(
        accountId: String,
        operationId: String,
        itemId: String,
        expectedCatalogRevision: Long,
        requestHash: String,
    ): Int
}

private fun validateMiniHomeCacheWrite(write: AuthoritativeMiniHomeCacheWrite) {
    require(write.accountId.isNotBlank())
    require(write.generation >= 1)
    require(write.authoritativeAtEpochMillis >= 0)
    when (write) {
        is AuthoritativeMiniHomeCacheWrite.Layout -> {
            require(write.generation >= 1)
            require(write.home.accountId == write.accountId)
            require(write.home.revision >= 1)
            require(write.operationId.matches(Regex("^[A-Za-z0-9_-]{8,128}$")))
            require(write.payloadHash.matches(Regex("^[a-f0-9]{64}$")))
            require(
                write.placements.all {
                    it.accountId == write.accountId &&
                        it.miniHomeId == write.home.miniHomeId &&
                        it.layoutRevision == write.home.revision
                }
            )
            require(write.placements.map { it.placementId }.toSet().size == write.placements.size)
            validateSnapshotIdentity(write.snapshotToken, write.snapshotGeneration)
        }
        is AuthoritativeMiniHomeCacheWrite.Deletion -> {
            require(write.tombstoneId.matches(Regex("^[A-Za-z0-9_-]{8,128}$")))
            validateSnapshotIdentity(write.snapshotToken, write.snapshotGeneration)
        }
    }
}

private fun AuthoritativeMiniHomeCacheWrite.watermark(): MiniHomeCacheWatermark =
    when (this) {
        is AuthoritativeMiniHomeCacheWrite.Layout ->
            MiniHomeCacheWatermark(
                accountId,
                generation,
                MiniHomeCacheWatermarkKind.PRESENT,
                home.revision,
                home.miniHomeId,
                operationId,
                payloadHash,
                null,
                authoritativeAtEpochMillis,
                verified = true,
                snapshotToken = snapshotToken,
                snapshotGeneration = snapshotGeneration,
            )
        is AuthoritativeMiniHomeCacheWrite.Deletion ->
            MiniHomeCacheWatermark(
                accountId,
                generation,
                MiniHomeCacheWatermarkKind.DELETED,
                null,
                null,
                null,
                null,
                tombstoneId,
                authoritativeAtEpochMillis,
                verified = true,
                snapshotToken = snapshotToken,
                snapshotGeneration = snapshotGeneration,
            )
    }

private fun MiniHomeCacheWatermarkEntity.watermark() =
    MiniHomeCacheWatermark(
        accountId,
        generation,
        MiniHomeCacheWatermarkKind.valueOf(kind),
        layoutRevision,
        miniHomeId,
        operationId,
        payloadHash,
        tombstoneId,
        authoritativeAtEpochMillis,
        verified,
        snapshotToken,
        snapshotGeneration,
    )

private fun MiniHomeCacheWatermark.entity() =
    MiniHomeCacheWatermarkEntity(
        accountId,
        generation,
        kind.name,
        layoutRevision,
        miniHomeId,
        operationId,
        payloadHash,
        tombstoneId,
        authoritativeAtEpochMillis,
        verified,
        snapshotToken,
        snapshotGeneration,
    )

private fun MiniHomeCacheWatermark.sameDomainIdentity(other: MiniHomeCacheWatermark): Boolean =
    accountId == other.accountId &&
        generation == other.generation &&
        kind == other.kind &&
        layoutRevision == other.layoutRevision &&
        miniHomeId == other.miniHomeId &&
        operationId == other.operationId &&
        payloadHash == other.payloadHash &&
        tombstoneId == other.tombstoneId &&
        verified == other.verified

private enum class CoherenceUpdate {
    APPLY,
    IGNORE,
    CONFLICT,
}

private fun coherenceUpdate(
    currentToken: String?,
    currentGeneration: Long?,
    candidateToken: String?,
    candidateGeneration: Long?,
): CoherenceUpdate =
    when {
        candidateToken == null -> CoherenceUpdate.IGNORE
        currentToken == null -> CoherenceUpdate.APPLY
        requireNotNull(candidateGeneration) > requireNotNull(currentGeneration) ->
            CoherenceUpdate.APPLY
        candidateGeneration < currentGeneration -> CoherenceUpdate.IGNORE
        candidateToken == currentToken -> CoherenceUpdate.IGNORE
        else -> CoherenceUpdate.CONFLICT
    }

private fun CachedMiniHomeLayoutState.sameContent(write: AuthoritativeMiniHomeCacheWrite): Boolean =
    when (write) {
        is AuthoritativeMiniHomeCacheWrite.Layout -> sameLayoutContent(write)
        is AuthoritativeMiniHomeCacheWrite.Deletion -> home == null && placements.isEmpty()
    }

private fun CachedMiniHomeLayoutState.sameLayoutContent(
    write: AuthoritativeMiniHomeCacheWrite.Layout
): Boolean = home == write.home && placements == write.placements

sealed interface OperationOutboxCompareAndSetResult {
    data class Updated(val operation: OperationOutboxEntity) : OperationOutboxCompareAndSetResult

    data class Stale(val current: OperationOutboxEntity?) : OperationOutboxCompareAndSetResult
}

sealed interface PersistedOperationDiscardResult {
    data class Consumed(val deletedRows: Int) : PersistedOperationDiscardResult

    data class Stale(val current: OperationOutboxEntity) : PersistedOperationDiscardResult

    data object Missing : PersistedOperationDiscardResult

    data object Rejected : PersistedOperationDiscardResult
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(entity: OperationOutboxEntity): Long

    @Query(
        "UPDATE operation_outbox SET draftPayload = :draftPayload, expectedRevision = :expectedRevision, mutationType = :mutationType, payloadHash = :payloadHash, lineageId = :lineageId, supersedesOperationId = :supersedesOperationId, state = 'PENDING', actualRevision = NULL, lastErrorCode = NULL, failureDetails = NULL, committedOperationId = NULL, committedExpectedRevision = NULL, committedRevision = NULL, committedPayloadHash = NULL, rowHandleId = lower(hex(randomblob(16))), rowVersion = 0 WHERE operationId = :operationId AND accountId = :accountId"
    )
    suspend fun mergeOperation(
        operationId: String,
        accountId: String,
        draftPayload: String,
        expectedRevision: Long,
        mutationType: String,
        payloadHash: String?,
        lineageId: String?,
        supersedesOperationId: String?,
    )

    @Transaction
    suspend fun enqueue(entity: OperationOutboxEntity) {
        if (insertOperation(entity) == -1L)
            mergeOperation(
                entity.operationId,
                entity.accountId,
                entity.draftPayload,
                entity.expectedRevision,
                entity.mutationType,
                entity.payloadHash,
                entity.lineageId,
                entity.supersedesOperationId,
            )
    }

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun operation(accountId: String, operationId: String): OperationOutboxEntity?

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND aggregateType = :aggregateType AND operationId = :rowOperationId AND lineageId IS :rowLineageId AND rowHandleId = :rowHandleId"
    )
    suspend fun operationByHandle(
        accountId: String,
        aggregateType: String,
        rowOperationId: String,
        rowLineageId: String?,
        rowHandleId: String,
    ): OperationOutboxEntity?

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND aggregateType = :aggregateType AND operationId = :rowOperationId AND lineageId IS :rowLineageId AND rowHandleId = :rowHandleId AND rowVersion = :rowVersion"
    )
    suspend fun operationByHandle(
        accountId: String,
        aggregateType: String,
        rowOperationId: String,
        rowLineageId: String?,
        rowHandleId: String,
        rowVersion: Long,
    ): OperationOutboxEntity?

    @Query(
        "UPDATE operation_outbox SET state = :state, actualRevision = :actualRevision, lastErrorCode = :lastErrorCode, failureDetails = :failureDetails, committedOperationId = :committedOperationId, committedExpectedRevision = :committedExpectedRevision, committedRevision = :committedRevision, payloadHash = :payloadHash, committedPayloadHash = :committedPayloadHash, rowVersion = rowVersion + 1 WHERE accountId = :accountId AND aggregateType = :aggregateType AND operationId = :operationId AND lineageId IS :lineageId AND rowHandleId = :rowHandleId AND rowVersion = :rowVersion"
    )
    suspend fun compareAndSetMutableOperationFields(
        accountId: String,
        aggregateType: String,
        operationId: String,
        lineageId: String?,
        rowHandleId: String,
        rowVersion: Long,
        state: String,
        actualRevision: Long?,
        lastErrorCode: String?,
        failureDetails: String?,
        committedOperationId: String?,
        committedExpectedRevision: Long?,
        committedRevision: Long?,
        payloadHash: String?,
        committedPayloadHash: String?,
    ): Int

    @Transaction
    suspend fun compareAndSetOperation(
        expected: OperationOutboxEntity,
        replacement: OperationOutboxEntity,
    ): OperationOutboxCompareAndSetResult {
        require(
            expected.operationId == replacement.operationId &&
                expected.accountId == replacement.accountId &&
                expected.aggregateType == replacement.aggregateType &&
                expected.aggregateId == replacement.aggregateId &&
                expected.mutationType == replacement.mutationType &&
                expected.expectedRevision == replacement.expectedRevision &&
                expected.draftPayload == replacement.draftPayload &&
                expected.createdAtEpochMillis == replacement.createdAtEpochMillis &&
                expected.lineageId == replacement.lineageId &&
                expected.supersedesOperationId == replacement.supersedesOperationId &&
                expected.rowHandleId == replacement.rowHandleId &&
                expected.rowVersion == replacement.rowVersion
        )
        val changed =
            compareAndSetMutableOperationFields(
                expected.accountId,
                expected.aggregateType,
                expected.operationId,
                expected.lineageId,
                expected.rowHandleId,
                expected.rowVersion,
                replacement.state,
                replacement.actualRevision,
                replacement.lastErrorCode,
                replacement.failureDetails,
                replacement.committedOperationId,
                replacement.committedExpectedRevision,
                replacement.committedRevision,
                replacement.payloadHash,
                replacement.committedPayloadHash,
            )
        return if (changed == 1) {
            OperationOutboxCompareAndSetResult.Updated(
                replacement.copy(rowVersion = expected.rowVersion + 1)
            )
        } else {
            OperationOutboxCompareAndSetResult.Stale(
                operation(expected.accountId, expected.operationId)
            )
        }
    }

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND state = 'PENDING' ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun ready(accountId: String): List<OperationOutboxEntity>

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND (state = 'PENDING' OR (state = 'FAILED' AND lastErrorCode IN (:transientCodes))) ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun replayable(
        accountId: String,
        transientCodes: Set<String>,
    ): List<OperationOutboxEntity>

    @Query(
        "SELECT * FROM operation_outbox WHERE accountId = :accountId AND state IN ('PENDING', 'MAY_HAVE_COMMITTED', 'RECONCILIATION_REQUIRED', 'CONFLICT', 'FAILED') ORDER BY createdAtEpochMillis ASC, operationId ASC"
    )
    suspend fun pending(accountId: String): List<OperationOutboxEntity>

    @Query(
        "DELETE FROM operation_outbox WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun remove(accountId: String, operationId: String): Int

    @Query(
        "DELETE FROM operation_outbox WHERE accountId = :accountId AND aggregateType = :aggregateType AND (lineageId = :lineageId OR (lineageId IS NULL AND operationId = :lineageId))"
    )
    suspend fun removeLineage(accountId: String, aggregateType: String, lineageId: String): Int

    @Query(
        "DELETE FROM operation_outbox WHERE accountId = :accountId AND aggregateType = :aggregateType AND lineageId = :lineageId"
    )
    suspend fun removePersistedLineage(
        accountId: String,
        aggregateType: String,
        lineageId: String,
    ): Int

    /**
     * Verifies the complete persisted anchor before deleting its row lineage. The transaction never
     * trusts IDs decoded from draftPayload and cannot cross an owner or aggregate boundary.
     */
    @Transaction
    suspend fun discardPersistedOperation(
        accountId: String,
        aggregateType: String,
        rowOperationId: String,
        rowLineageId: String?,
        rowHandleId: String,
        rowVersion: Long,
    ): PersistedOperationDiscardResult {
        if (aggregateType.isBlank() || rowHandleId.isBlank() || rowVersion < 0) {
            return PersistedOperationDiscardResult.Rejected
        }
        val current =
            operation(accountId, rowOperationId) ?: return PersistedOperationDiscardResult.Missing
        if (current.aggregateType != aggregateType) {
            return PersistedOperationDiscardResult.Rejected
        }
        if (
            current.lineageId != rowLineageId ||
                current.rowHandleId != rowHandleId ||
                current.rowVersion != rowVersion
        ) {
            return PersistedOperationDiscardResult.Stale(current)
        }
        val deleted =
            if (rowLineageId == null) {
                remove(accountId, rowOperationId)
            } else {
                removePersistedLineage(accountId, aggregateType, rowLineageId)
            }
        return if (deleted > 0) {
            PersistedOperationDiscardResult.Consumed(deleted)
        } else {
            operation(accountId, rowOperationId)?.let(PersistedOperationDiscardResult::Stale)
                ?: PersistedOperationDiscardResult.Missing
        }
    }

    @Query(
        "UPDATE operation_outbox SET state = 'CONFLICT', actualRevision = :actualRevision, lastErrorCode = 'REVISION_CONFLICT' WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markConflict(accountId: String, operationId: String, actualRevision: Long)

    @Query(
        "UPDATE operation_outbox SET state = 'FAILED', lastErrorCode = :code WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markFailed(accountId: String, operationId: String, code: String)

    @Query(
        "UPDATE operation_outbox SET state = 'MAY_HAVE_COMMITTED', lastErrorCode = :reason, failureDetails = :details WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markMayHaveCommitted(
        accountId: String,
        operationId: String,
        reason: String?,
        details: String?,
    )

    @Query(
        "UPDATE operation_outbox SET committedOperationId = :committedOperationId, committedExpectedRevision = :committedExpectedRevision, committedRevision = :committedRevision, committedPayloadHash = :committedPayloadHash WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun recordCommittedReceipt(
        accountId: String,
        operationId: String,
        committedOperationId: String,
        committedExpectedRevision: Long,
        committedRevision: Long,
        committedPayloadHash: String,
    )

    @Query(
        "UPDATE operation_outbox SET payloadHash = :payloadHash WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun recordPayloadHash(accountId: String, operationId: String, payloadHash: String)

    @Query(
        "UPDATE operation_outbox SET state = 'PENDING', lastErrorCode = :reason, failureDetails = :details, actualRevision = NULL, committedOperationId = NULL, committedExpectedRevision = NULL, committedRevision = NULL, committedPayloadHash = NULL WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markPending(
        accountId: String,
        operationId: String,
        reason: String?,
        details: String?,
    )

    @Query(
        "UPDATE operation_outbox SET state = 'RECONCILIATION_REQUIRED', lastErrorCode = :reason, failureDetails = :details, actualRevision = :actualRevision, committedOperationId = :committedOperationId, committedExpectedRevision = :committedExpectedRevision, committedRevision = :committedRevision, committedPayloadHash = :committedPayloadHash WHERE accountId = :accountId AND operationId = :operationId"
    )
    suspend fun markReconciliationRequired(
        accountId: String,
        operationId: String,
        reason: String,
        details: String?,
        actualRevision: Long?,
        committedOperationId: String?,
        committedExpectedRevision: Long?,
        committedRevision: Long?,
        committedPayloadHash: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLastSync(entity: LastSyncEntity)

    @Query("SELECT * FROM last_sync WHERE accountId = :accountId AND domain = :domain")
    suspend fun lastSync(accountId: String, domain: String): LastSyncEntity?
}
