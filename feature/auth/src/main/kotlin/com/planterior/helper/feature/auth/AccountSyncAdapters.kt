package com.planterior.helper.feature.auth

import com.google.firebase.firestore.FirebaseFirestore
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import java.time.Instant

data class RemotePlant(
    val id: String,
    val displayName: String,
    val representativePhotoPath: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

data class RemoteWateringSchedule(
    val id: String,
    val plantId: String,
    val dueDate: String,
    val reminderTime: String,
    val zoneId: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

interface AccountSyncRemote {
    suspend fun plants(accountUid: String): List<RemotePlant>

    suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule>

    suspend fun verifyDomain(accountUid: String, domain: SyncDomain)
}

class FirestoreAccountSyncRemote(private val firestore: FirebaseFirestore) : AccountSyncRemote {
    override suspend fun plants(accountUid: String): List<RemotePlant> =
        firestore
            .collection("users/$accountUid/personalPlants")
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                val displayName = document.getString("displayName") ?: return@mapNotNull null
                RemotePlant(
                    document.id,
                    displayName,
                    document.getString("representativePhotoPath"),
                    document.getLong("revision") ?: 0L,
                    document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                )
            }

    override suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule> =
        firestore
            .collection("users/$accountUid/wateringSchedules")
            .get()
            .await()
            .documents
            .mapNotNull { document ->
                val plantId = document.getString("plantId") ?: return@mapNotNull null
                val dueDate = document.getString("dueDate") ?: return@mapNotNull null
                val reminderTime = document.getString("reminderTime") ?: return@mapNotNull null
                val zoneId = document.getString("zoneId") ?: return@mapNotNull null
                RemoteWateringSchedule(
                    document.id,
                    plantId,
                    dueDate,
                    reminderTime,
                    zoneId,
                    document.getLong("revision") ?: 0L,
                    document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                )
            }

    override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) {
        val collection =
            when (domain) {
                SyncDomain.NOTIFICATIONS -> "notificationSettings"
                SyncDomain.MINI_HOME -> "miniHomes"
                else -> error("$domain has a typed snapshot operation")
            }
        firestore.collection("users/$accountUid/$collection").get().await()
    }
}

class RoomAccountSessionCache(private val repository: OfflineFirstSyncRepository) :
    AccountSessionCache {
    override suspend fun clearVisible(accountUid: String?) {
        repository.deactivate()
    }

    override fun activate(accountUid: String?) {
        if (accountUid == null) repository.deactivate()
        else repository.activate(AccountId(accountUid))
    }
}

class FirestoreAccountSynchronizer(
    private val remote: AccountSyncRemote,
    private val database: PlanteriorDatabase,
    private val now: () -> Instant = Instant::now,
) : AccountSynchronizer {
    constructor(
        firestore: FirebaseFirestore,
        database: PlanteriorDatabase,
        now: () -> Instant = Instant::now,
    ) : this(FirestoreAccountSyncRemote(firestore), database, now)

    override suspend fun sync(accountUid: String): SyncSummary {
        require(accountUid.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        syncDomain(accountUid, SyncDomain.PLANTS) {
            val entities =
                remote.plants(accountUid).map {
                    CachedPlantEntity(
                        accountUid,
                        it.id,
                        it.displayName,
                        it.representativePhotoPath,
                        it.revision,
                        it.updatedAtEpochMillis,
                    )
                }
            database.cacheDao().reconcilePlants(accountUid, entities)
        }
        syncDomain(accountUid, SyncDomain.WATERING) {
            val entities =
                remote.wateringSchedules(accountUid).map {
                    CachedWateringScheduleEntity(
                        accountUid,
                        it.id,
                        it.plantId,
                        it.dueDate,
                        it.reminderTime,
                        it.zoneId,
                        it.revision,
                        it.updatedAtEpochMillis,
                    )
                }
            database.cacheDao().reconcileSchedules(accountUid, entities)
        }
        syncDomain(accountUid, SyncDomain.NOTIFICATIONS) {
            remote.verifyDomain(accountUid, SyncDomain.NOTIFICATIONS)
        }
        syncDomain(accountUid, SyncDomain.MINI_HOME) {
            remote.verifyDomain(accountUid, SyncDomain.MINI_HOME)
        }
        return lastKnown(accountUid)
    }

    override suspend fun lastKnown(accountUid: String): SyncSummary {
        require(accountUid.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        val records =
            SyncDomain.entries
                .mapNotNull { domain ->
                    database.syncDao().lastSync(accountUid, domain.name)?.let { entity ->
                        domain to
                            SyncRecord(
                                Instant.ofEpochMilli(entity.syncedAtEpochMillis),
                                SyncStatus.valueOf(entity.status),
                                entity.errorCode,
                            )
                    }
                }
                .toMap()
        val completed = records.filterValues { it.status == SyncStatus.SUCCESS }.keys
        val failures =
            records
                .filterValues { it.status == SyncStatus.FAILED }
                .mapValues { it.value.errorCode ?: "unavailable" }
        return SyncSummary(completed, failures, records)
    }

    private suspend fun syncDomain(
        accountUid: String,
        domain: SyncDomain,
        block: suspend () -> Unit,
    ) {
        val attemptedAt = now().toEpochMilli()
        val record =
            try {
                block()
                LastSyncEntity(accountUid, domain.name, attemptedAt, SyncStatus.SUCCESS.name, null)
            } catch (_: Exception) {
                LastSyncEntity(
                    accountUid,
                    domain.name,
                    attemptedAt,
                    SyncStatus.FAILED.name,
                    "unavailable",
                )
            }
        database.syncDao().upsertLastSync(record)
    }
}
