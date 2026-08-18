package com.planterior.helper.feature.auth

import com.google.firebase.firestore.FirebaseFirestore
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import java.time.Instant
import kotlinx.coroutines.CancellationException

data class RemotePlant(
    val id: String,
    val displayName: String,
    val representativePhotoPath: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val contentId: String? = null,
    val registrationMethod: String = "MANUAL",
    val location: String? = null,
    val note: String? = null,
    val lastWateredDate: String? = null,
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

/**
 * 홈 미리보기에 필요한 만큼의 서버 미니홈피 구성이다.
 *
 * 좌표·z-order·아이템 목록은 미니홈피 화면의 몫이므로 여기서 가져오지 않는다.
 */
data class RemoteMiniHome(
    val id: String,
    val name: String,
    val placedPlantCount: Int,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

interface AccountSyncRemote {
    suspend fun plants(accountUid: String): List<RemotePlant>

    suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule>

    /** 마지막으로 확정된 미니홈피. 아직 만들지 않았거나 삭제되었으면 `null`. */
    suspend fun miniHome(accountUid: String): RemoteMiniHome?

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
                    id = document.id,
                    displayName = displayName,
                    representativePhotoPath = document.getString("representativePhotoPath"),
                    revision = document.getLong("revision") ?: 0L,
                    updatedAtEpochMillis = document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                    contentId = document.getString("contentId"),
                    registrationMethod = document.getString("registrationMethod") ?: "MANUAL",
                    location = document.getString("location"),
                    note = document.getString("note"),
                    lastWateredDate = document.getString("lastWateredDate"),
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

    override suspend fun miniHome(accountUid: String): RemoteMiniHome? =
        firestore
            .collection("users/$accountUid/miniHomes")
            .get()
            .await()
            .documents
            .asSequence()
            .mapNotNull { document ->
                val name = document.getString("name") ?: return@mapNotNull null
                RemoteMiniHome(
                    document.id,
                    name,
                    (document.getLong("placedPlantCount") ?: 0L).toInt(),
                    document.getLong("revision") ?: 0L,
                    document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                )
            }
            // 계정당 미니홈피는 하나다. 예기치 않게 여럿이면 revision이 가장 높은 확정본을 쓴다.
            .maxByOrNull { it.revision }

    override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) {
        val collection =
            when (domain) {
                SyncDomain.NOTIFICATIONS -> "notificationSettings"
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
    private val outbox: OfflineFirstSyncRepository? = null,
    private val now: () -> Instant = Instant::now,
) : AccountSynchronizer {
    constructor(
        firestore: FirebaseFirestore,
        database: PlanteriorDatabase,
        outbox: OfflineFirstSyncRepository? = null,
        now: () -> Instant = Instant::now,
    ) : this(FirestoreAccountSyncRemote(firestore), database, outbox, now)

    override suspend fun sync(accountUid: String): SyncSummary {
        require(accountUid.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        syncDomain(accountUid, SyncDomain.PLANTS) {
            val replay = outbox?.sync(AccountId(accountUid))
            val entities =
                remote.plants(accountUid).map {
                    CachedPlantEntity(
                        accountId = accountUid,
                        plantId = it.id,
                        displayName = it.displayName,
                        representativePhotoPath = it.representativePhotoPath,
                        revision = it.revision,
                        updatedAtEpochMillis = it.updatedAtEpochMillis,
                        contentId = it.contentId,
                        registrationMethod = it.registrationMethod,
                        location = it.location,
                        note = it.note,
                        lastWateredDate = it.lastWateredDate,
                        detailsComplete = true,
                    )
                }
            database.cacheDao().reconcilePlants(accountUid, entities)
            check(replay == null || (replay.conflicts == 0 && replay.failed == 0)) {
                "Outbox replay did not reach an authoritative result"
            }
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
            val remoteMiniHome = remote.miniHome(accountUid)
            database
                .cacheDao()
                .reconcileMiniHome(
                    accountUid,
                    remoteMiniHome?.let {
                        CachedMiniHomeEntity(
                            accountUid,
                            it.id,
                            it.name,
                            it.placedPlantCount,
                            it.revision,
                            it.updatedAtEpochMillis,
                        )
                    },
                )
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
            } catch (error: CancellationException) {
                throw error
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
