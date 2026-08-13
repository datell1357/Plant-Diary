package com.planterior.helper.feature.auth

import com.google.firebase.firestore.FirebaseFirestore
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId

class RoomAccountSessionCache(
    private val database: PlanteriorDatabase,
    private val repository: OfflineFirstSyncRepository,
) : AccountSessionCache {
    override suspend fun clearVisible(accountUid: String?) {
        repository.deactivate()
        if (accountUid != null) database.cacheDao().clearVisibleAccount(accountUid)
    }

    override fun activate(accountUid: String?) {
        if (accountUid == null) repository.deactivate()
        else repository.activate(AccountId(accountUid))
    }
}

class FirestoreAccountSynchronizer(
    private val firestore: FirebaseFirestore,
    private val database: PlanteriorDatabase,
) : AccountSynchronizer {
    override suspend fun sync(accountUid: String): SyncSummary {
        require(accountUid.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        val completed = mutableSetOf<SyncDomain>()
        val failed = mutableMapOf<SyncDomain, String>()
        syncDomain(SyncDomain.PLANTS, completed, failed) { syncPlants(accountUid) }
        syncDomain(SyncDomain.WATERING, completed, failed) { syncWatering(accountUid) }
        syncDomain(SyncDomain.NOTIFICATIONS, completed, failed) {
            firestore.collection("users/$accountUid/notificationSettings").get().await()
        }
        syncDomain(SyncDomain.MINI_HOME, completed, failed) {
            firestore.collection("users/$accountUid/miniHomes").get().await()
        }
        return SyncSummary(completed, failed)
    }

    private suspend fun syncPlants(uid: String) {
        val snapshot = firestore.collection("users/$uid/personalPlants").get().await()
        snapshot.documents.forEach { document ->
            val name = document.getString("displayName") ?: return@forEach
            database
                .cacheDao()
                .upsertPlant(
                    CachedPlantEntity(
                        uid,
                        document.id,
                        name,
                        document.getString("representativePhotoPath"),
                        document.getLong("revision") ?: 0L,
                        document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                    )
                )
        }
    }

    private suspend fun syncWatering(uid: String) {
        val snapshot = firestore.collection("users/$uid/wateringSchedules").get().await()
        snapshot.documents.forEach { document ->
            val plantId = document.getString("plantId") ?: return@forEach
            val dueDate = document.getString("dueDate") ?: return@forEach
            val reminderTime = document.getString("reminderTime") ?: return@forEach
            val zoneId = document.getString("zoneId") ?: return@forEach
            database
                .cacheDao()
                .upsertSchedule(
                    CachedWateringScheduleEntity(
                        uid,
                        document.id,
                        plantId,
                        dueDate,
                        reminderTime,
                        zoneId,
                        document.getLong("revision") ?: 0L,
                        document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                    )
                )
        }
    }

    private suspend fun syncDomain(
        domain: SyncDomain,
        completed: MutableSet<SyncDomain>,
        failed: MutableMap<SyncDomain, String>,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            completed += domain
        } catch (_: Exception) {
            failed[domain] = "unavailable"
        }
    }
}
