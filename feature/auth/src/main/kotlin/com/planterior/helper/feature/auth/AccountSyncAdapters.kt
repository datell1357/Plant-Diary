package com.planterior.helper.feature.auth

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.planterior.helper.core.data.AuthoritativeMiniHomeLayoutRead
import com.planterior.helper.core.data.AuthoritativeMiniHomeLayoutReader
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.database.AuthoritativeMiniHomeCacheWrite
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedMiniHomePlacementEntity
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.CachedWateringScheduleEntity
import com.planterior.helper.core.database.LastSyncEntity
import com.planterior.helper.core.database.MiniHomeCacheApplyResult
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
    val reminderTime: String?,
    val zoneId: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val enabled: Boolean? = null,
)

data class RemoteMiniHomePlacement(
    val id: String,
    val plantId: String?,
    val itemId: String?,
    val normalizedX: Double,
    val normalizedY: Double,
    val zIndex: Int,
    val layoutRevision: Long,
)

/** 홈 preview와 상세 화면이 함께 쓰는 마지막 서버 확정 구성이다. */
data class RemoteMiniHome(
    val id: String,
    val name: String,
    val placedPlantCount: Int,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val placements: List<RemoteMiniHomePlacement> = emptyList(),
)

data class RemoteMiniHomeAuthoritativeState(
    val generation: Long,
    val layout: RemoteMiniHome?,
    val operationId: String?,
    val payloadHash: String?,
    val tombstoneId: String?,
    val authoritativeAtEpochMillis: Long,
)

interface AccountSyncRemote {
    suspend fun plants(accountUid: String): List<RemotePlant>

    suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule>

    /** 마지막으로 확정된 미니홈피. 아직 만들지 않았거나 삭제되었으면 `null`. */
    suspend fun miniHome(accountUid: String): RemoteMiniHome?

    suspend fun miniHomeAuthoritativeState(accountUid: String): RemoteMiniHomeAuthoritativeState {
        val layout = miniHome(accountUid)
        return RemoteMiniHomeAuthoritativeState(
            generation = maxOf(1, layout?.revision ?: 0),
            layout = layout,
            operationId = layout?.let { "legacy-sync-${it.revision}" },
            payloadHash = layout?.let { "0".repeat(64) },
            tombstoneId = if (layout == null) "initial-missing" else null,
            authoritativeAtEpochMillis = layout?.updatedAtEpochMillis ?: 0,
        )
    }

    suspend fun verifyDomain(accountUid: String, domain: SyncDomain)
}

class FirestoreAccountSyncRemote(
    private val firestore: FirebaseFirestore,
    functions: FirebaseFunctions,
    private val miniHomeReader: AuthoritativeMiniHomeLayoutReader =
        AuthoritativeMiniHomeLayoutReader(functions),
) : AccountSyncRemote {
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

    override suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule> {
        val account = firestore.document("users/$accountUid").get().await()
        val zoneId = requireNotNull(account.getString("zoneId"))
        val schedules =
            firestore.collection("users/$accountUid/wateringSchedules").get().await().documents
        val preferences =
            firestore
                .collection("users/$accountUid/notificationPlantSettings")
                .get()
                .await()
                .documents
                .associateBy { it.id }
        return schedules.mapNotNull { document ->
            val plantId = document.getString("plantId") ?: return@mapNotNull null
            val dueDate = document.getString("dueDate") ?: return@mapNotNull null
            val preference = preferences[plantId]
            RemoteWateringSchedule(
                document.id,
                plantId,
                dueDate,
                preference?.getString("timeOverride"),
                zoneId,
                document.getLong("revision") ?: 0L,
                document.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                preference?.getBoolean("enabled"),
            )
        }
    }

    override suspend fun miniHome(accountUid: String): RemoteMiniHome? =
        miniHomeAuthoritativeState(accountUid).layout

    override suspend fun miniHomeAuthoritativeState(
        accountUid: String
    ): RemoteMiniHomeAuthoritativeState =
        when (val result = miniHomeReader.read(AccountId(accountUid))) {
            is AuthoritativeMiniHomeLayoutRead.Missing ->
                RemoteMiniHomeAuthoritativeState(
                    result.generation,
                    null,
                    null,
                    null,
                    result.tombstoneId,
                    result.updatedAtEpochMillis,
                )
            is AuthoritativeMiniHomeLayoutRead.Present -> {
                val home = result.layout
                RemoteMiniHomeAuthoritativeState(
                    generation = home.generation,
                    layout =
                        RemoteMiniHome(
                            home.id,
                            home.name,
                            home.placedPlantCount,
                            home.revision,
                            home.updatedAtEpochMillis,
                            home.placements.map { placement ->
                                RemoteMiniHomePlacement(
                                    placement.id,
                                    placement.plantId,
                                    placement.itemId,
                                    placement.normalizedX,
                                    placement.normalizedY,
                                    placement.zIndex,
                                    placement.revision,
                                )
                            },
                        ),
                    operationId = home.idempotencyKey,
                    payloadHash = home.requestHash,
                    tombstoneId = null,
                    authoritativeAtEpochMillis = home.updatedAtEpochMillis,
                )
            }
        }

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
        functions: FirebaseFunctions,
        database: PlanteriorDatabase,
        outbox: OfflineFirstSyncRepository? = null,
        now: () -> Instant = Instant::now,
    ) : this(FirestoreAccountSyncRemote(firestore, functions), database, outbox, now)

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
                        it.enabled,
                    )
                }
            database.cacheDao().reconcileSchedules(accountUid, entities)
        }
        syncDomain(accountUid, SyncDomain.NOTIFICATIONS) {
            remote.verifyDomain(accountUid, SyncDomain.NOTIFICATIONS)
        }
        syncDomain(accountUid, SyncDomain.MINI_HOME) {
            val authoritative = remote.miniHomeAuthoritativeState(accountUid)
            val remoteMiniHome = authoritative.layout
            val write =
                if (remoteMiniHome == null) {
                    AuthoritativeMiniHomeCacheWrite.Deletion(
                        accountUid,
                        authoritative.generation,
                        requireNotNull(authoritative.tombstoneId),
                        authoritative.authoritativeAtEpochMillis,
                    )
                } else {
                    AuthoritativeMiniHomeCacheWrite.Layout(
                        accountUid,
                        authoritative.generation,
                        requireNotNull(authoritative.operationId),
                        requireNotNull(authoritative.payloadHash),
                        CachedMiniHomeEntity(
                            accountUid,
                            remoteMiniHome.id,
                            remoteMiniHome.name,
                            remoteMiniHome.placedPlantCount,
                            remoteMiniHome.revision,
                            remoteMiniHome.updatedAtEpochMillis,
                        ),
                        remoteMiniHome.placements.map {
                            CachedMiniHomePlacementEntity(
                                accountUid,
                                it.id,
                                remoteMiniHome.id,
                                it.plantId,
                                it.itemId,
                                it.normalizedX,
                                it.normalizedY,
                                it.zIndex,
                                it.layoutRevision,
                            )
                        },
                    )
                }
            check(
                database.cacheDao().applyAuthoritativeMiniHome(write)
                    !is MiniHomeCacheApplyResult.Conflict
            ) {
                "Authoritative mini-home cache identity conflicted"
            }
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
