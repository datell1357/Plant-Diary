package com.planterior.helper.minihome

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.planterior.helper.core.database.AuthoritativeMiniHomeCacheWrite
import com.planterior.helper.core.database.CachedMiniHomeEntity
import com.planterior.helper.core.database.CachedMiniHomePlacementEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PersistedOperationDiscardResult
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeDecorationChoice
import com.planterior.helper.feature.minihome.MiniHomeDiscardHandle
import com.planterior.helper.feature.minihome.MiniHomeDiscardResult
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.minihome.MiniHomePlacement
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeSaveFailure
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.minihome.MiniHomeZIndex
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

fun debugMiniHomeRepository(
    context: Context,
    database: PlanteriorDatabase,
    fallback: MiniHomeRepository,
): MiniHomeRepository {
    forcedAccount(context) ?: return fallback
    return DebugRoomMiniHomeRepository(context.applicationContext, database)
}

fun setDebugMiniHomeSaveOutcome(context: Context, outcome: String) {
    preferences(context).edit(commit = true) { putString(SAVE_OUTCOME, outcome) }
}

data class DebugMiniHomeLoadEvent(
    val sequence: Long,
    val accountId: String,
    val miniHomeId: String,
    val revision: Long,
    val placementIds: List<String>,
)

fun subscribeToDebugMiniHomeLoads(listener: (DebugMiniHomeLoadEvent) -> Unit): AutoCloseable =
    DebugMiniHomeLoadEvents.subscribe(listener)

private object DebugMiniHomeLoadEvents {
    private val sequence = AtomicLong()
    private val listeners = CopyOnWriteArraySet<(DebugMiniHomeLoadEvent) -> Unit>()

    fun subscribe(listener: (DebugMiniHomeLoadEvent) -> Unit): AutoCloseable {
        check(listeners.add(listener)) { "debug mini-home load listener is already registered" }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(accountId: AccountId, layout: MiniHomeLayout) {
        val event =
            DebugMiniHomeLoadEvent(
                sequence.incrementAndGet(),
                accountId.value,
                layout.id.value,
                layout.revision.value,
                layout.placements.map { it.id.value },
            )
        Log.i(
            LOAD_EVENT_LOG_TAG,
            "sequence=${event.sequence} account=${event.accountId} home=${event.miniHomeId} " +
                "revision=${event.revision} placements=${event.placementIds.joinToString(",")}",
        )
        listeners.forEach { it(event) }
    }
}

enum class DebugMiniHomeStateMode {
    LOADING,
    VIEWING,
    EDITING,
    FORBIDDEN,
    UNAVAILABLE,
    ERROR,
}

enum class DebugMiniHomeSaveStateMode {
    IDLE,
    SAVING,
    FAILED,
    VALIDATION_FAILED,
    RECONCILIATION_REQUIRED,
    CORRECTED,
    CONFLICT,
}

data class DebugMiniHomePlacementSnapshot(
    val placementId: String,
    val targetId: String,
    val column: Int,
    val row: Int,
    val zIndex: Int,
)

data class DebugMiniHomeStateSnapshot(
    val accountId: String,
    val mode: DebugMiniHomeStateMode,
    val saveState: DebugMiniHomeSaveStateMode,
    val name: String?,
    val committedRevision: Long?,
    val layoutRevision: Long?,
    val placements: List<DebugMiniHomePlacementSnapshot>,
)

data class DebugMiniHomeStateEvent(
    val sequence: Long,
    val activityIdentity: Int,
    val snapshot: DebugMiniHomeStateSnapshot,
)

fun currentDebugMiniHomeState(): DebugMiniHomeStateEvent? = DebugMiniHomeStateEvents.current()

fun subscribeToDebugMiniHomeStates(listener: (DebugMiniHomeStateEvent) -> Unit): AutoCloseable =
    DebugMiniHomeStateEvents.subscribe(listener)

fun observeDebugMiniHomeState(context: Context, state: MiniHomeUiState) {
    val accountId = forcedAccount(context).orEmpty()
    val snapshot =
        when (state) {
            is MiniHomeUiState.Loading ->
                DebugMiniHomeStateSnapshot(
                    state.accountId?.value.orEmpty(),
                    DebugMiniHomeStateMode.LOADING,
                    DebugMiniHomeSaveStateMode.IDLE,
                    null,
                    null,
                    null,
                    emptyList(),
                )
            is MiniHomeUiState.Viewing ->
                DebugMiniHomeStateSnapshot(
                    state.owner.value,
                    DebugMiniHomeStateMode.VIEWING,
                    DebugMiniHomeSaveStateMode.IDLE,
                    state.committed.name,
                    state.committed.revision.value,
                    state.committed.revision.value,
                    state.committed.debugPlacements(),
                )
            is MiniHomeUiState.Editing ->
                DebugMiniHomeStateSnapshot(
                    state.owner.value,
                    DebugMiniHomeStateMode.EDITING,
                    state.saveState.debugMode(),
                    state.draft.name,
                    state.committed.revision.value,
                    state.draft.revision.value,
                    state.draft.debugPlacements(),
                )
            MiniHomeUiState.Forbidden ->
                DebugMiniHomeStateSnapshot(
                    accountId,
                    DebugMiniHomeStateMode.FORBIDDEN,
                    DebugMiniHomeSaveStateMode.IDLE,
                    null,
                    null,
                    null,
                    emptyList(),
                )
            is MiniHomeUiState.Unavailable ->
                DebugMiniHomeStateSnapshot(
                    state.accountId.value,
                    DebugMiniHomeStateMode.UNAVAILABLE,
                    DebugMiniHomeSaveStateMode.IDLE,
                    null,
                    null,
                    null,
                    emptyList(),
                )
            MiniHomeUiState.Error ->
                DebugMiniHomeStateSnapshot(
                    accountId,
                    DebugMiniHomeStateMode.ERROR,
                    DebugMiniHomeSaveStateMode.IDLE,
                    null,
                    null,
                    null,
                    emptyList(),
                )
        }
    DebugMiniHomeStateEvents.publish(System.identityHashCode(context), snapshot)
}

private object DebugMiniHomeStateEvents {
    private val sequence = AtomicLong()
    private val latest = AtomicReference<DebugMiniHomeStateEvent>()
    private val listeners = CopyOnWriteArraySet<(DebugMiniHomeStateEvent) -> Unit>()

    fun current(): DebugMiniHomeStateEvent? = latest.get()

    fun subscribe(listener: (DebugMiniHomeStateEvent) -> Unit): AutoCloseable {
        check(listeners.add(listener)) { "debug mini-home state listener is already registered" }
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(activityIdentity: Int, snapshot: DebugMiniHomeStateSnapshot) {
        val event = DebugMiniHomeStateEvent(sequence.incrementAndGet(), activityIdentity, snapshot)
        latest.set(event)
        Log.i(
            STATE_EVENT_LOG_TAG,
            "sequence=${event.sequence} activity=${event.activityIdentity} " +
                "account=${snapshot.accountId} mode=${snapshot.mode} saveState=${snapshot.saveState} " +
                "name=${snapshot.name} " +
                "committedRevision=${snapshot.committedRevision} " +
                "layoutRevision=${snapshot.layoutRevision} placements=${snapshot.placements}",
        )
        listeners.forEach { it(event) }
    }
}

private fun MiniHomeSaveState.debugMode(): DebugMiniHomeSaveStateMode =
    when (this) {
        MiniHomeSaveState.Idle -> DebugMiniHomeSaveStateMode.IDLE
        MiniHomeSaveState.Saving -> DebugMiniHomeSaveStateMode.SAVING
        is MiniHomeSaveState.Failed -> DebugMiniHomeSaveStateMode.FAILED
        is MiniHomeSaveState.ValidationFailed -> DebugMiniHomeSaveStateMode.VALIDATION_FAILED
        is MiniHomeSaveState.ReconciliationRequired ->
            DebugMiniHomeSaveStateMode.RECONCILIATION_REQUIRED
        is MiniHomeSaveState.Corrected -> DebugMiniHomeSaveStateMode.CORRECTED
        MiniHomeSaveState.Conflict -> DebugMiniHomeSaveStateMode.CONFLICT
    }

private fun MiniHomeLayout.debugPlacements(): List<DebugMiniHomePlacementSnapshot> =
    placements
        .map { placement ->
            DebugMiniHomePlacementSnapshot(
                placement.id.value,
                when (val target = placement.target) {
                    is MiniHomePlacementTarget.Plant -> target.plantId.value
                    is MiniHomePlacementTarget.Decoration -> target.itemId.value
                },
                placement.position.column,
                placement.position.row,
                placement.zIndex.value,
            )
        }
        .sortedBy { it.placementId }

private class DebugRoomMiniHomeRepository(
    private val context: Context,
    private val database: PlanteriorDatabase,
) : MiniHomeRepository {
    override suspend fun load(): MiniHomeLoadResult {
        val account =
            forcedAccount(context)?.let(::AccountId) ?: return MiniHomeLoadResult.Forbidden
        val home = database.cacheDao().miniHome(account.value)
        val layout =
            if (home == null) {
                MiniHomeLayout(
                    MiniHomeId("primary"),
                    "나의 미니 식물원",
                    emptyList(),
                    Revision(0),
                    Instant.EPOCH,
                )
            } else {
                MiniHomeLayout(
                    MiniHomeId(home.miniHomeId),
                    home.name,
                    database
                        .cacheDao()
                        .miniHomePlacements(account.value, home.miniHomeId, home.revision)
                        .map { it.placement() },
                    Revision(home.revision),
                    Instant.ofEpochMilli(home.updatedAtEpochMillis),
                )
            }
        val result =
            MiniHomeLoadResult.Ready(
                account,
                layout,
                database.cacheDao().plants(account.value).map {
                    MiniHomePlantChoice(
                        PersonalPlantId(it.plantId),
                        it.displayName,
                        it.representativePhotoPath,
                    )
                },
                listOf(
                    MiniHomeDecorationChoice(ItemId("decor-lamp"), "원목 스탠드"),
                    MiniHomeDecorationChoice(ItemId("decor-rug"), "초록 러그"),
                ),
                stale = false,
                pending = null,
            )
        DebugMiniHomeLoadEvents.publish(account, layout)
        return result
    }

    override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult {
        val account =
            forcedAccount(context)?.let(::AccountId) ?: return MiniHomeSaveResult.Forbidden
        if (request.accountId != account) return MiniHomeSaveResult.Forbidden
        return when (preferences(context).getString(SAVE_OUTCOME, OUTCOME_SUCCESS)) {
            OUTCOME_FAILURE -> MiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
            OUTCOME_UNAVAILABLE ->
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.UNAVAILABLE_ENTITY)
            OUTCOME_MISMATCH ->
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.OUTBOX_MISMATCH)
            OUTCOME_INVALID ->
                MiniHomeSaveResult.RequiresCorrection(
                    MiniHomeSaveFailure.INVALID_REQUEST,
                    "field=name;reason=INVALID_REQUEST",
                )
            OUTCOME_CONFLICT -> {
                val current = load() as MiniHomeLoadResult.Ready
                val authoritative =
                    current.committed.copy(
                        name = "다른 기기에서 저장한 방",
                        revision = current.committed.revision.next(),
                        updatedAt = Instant.now(),
                    )
                persist(account, authoritative)
                MiniHomeSaveResult.RequiresReconciliation(MiniHomeSaveFailure.REVISION_CONFLICT)
            }
            else -> {
                val saved =
                    request.layout.copy(
                        revision = request.expectedRevision.next(),
                        updatedAt = Instant.now(),
                    )
                persist(account, saved)
                MiniHomeSaveResult.Saved(saved)
            }
        }
    }

    override suspend fun abandonPending(
        accountId: AccountId,
        operationId: OperationId?,
    ): MiniHomeDiscardResult {
        val account =
            forcedAccount(context)?.let(::AccountId) ?: return MiniHomeDiscardResult.OwnerMismatch
        if (account != accountId) return MiniHomeDiscardResult.OwnerMismatch
        return try {
            val operations =
                database.syncDao().pending(account.value).filter {
                    it.aggregateType == MINI_HOME_OUTBOX_TYPE
                }
            val superseded = operations.mapNotNullTo(mutableSetOf()) { it.supersedesOperationId }
            val current =
                operations
                    .filterNot { it.operationId in superseded }
                    .maxWithOrNull(
                        compareBy<OperationOutboxEntity> { it.createdAtEpochMillis }
                            .thenBy { it.operationId }
                    ) ?: return MiniHomeDiscardResult.Missing
            when (
                database
                    .syncDao()
                    .discardPersistedOperation(
                        current.accountId,
                        current.aggregateType,
                        current.operationId,
                        current.lineageId,
                        current.rowHandleId,
                        current.rowVersion,
                    )
            ) {
                is PersistedOperationDiscardResult.Consumed -> MiniHomeDiscardResult.Consumed
                is PersistedOperationDiscardResult.Stale -> MiniHomeDiscardResult.StaleHandle(null)
                PersistedOperationDiscardResult.Missing ->
                    if (
                        database.syncDao().pending(account.value).none {
                            it.aggregateType == MINI_HOME_OUTBOX_TYPE
                        }
                    ) {
                        MiniHomeDiscardResult.Missing
                    } else {
                        MiniHomeDiscardResult.StaleHandle(null)
                    }
                PersistedOperationDiscardResult.Rejected -> MiniHomeDiscardResult.Rejected
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MiniHomeDiscardResult.Rejected
        }
    }

    override suspend fun abandon(handle: MiniHomeDiscardHandle): MiniHomeDiscardResult =
        MiniHomeDiscardResult.Missing

    override suspend fun reconcile(
        request: MiniHomeSaveRequest,
        failure: MiniHomeSaveFailure,
    ): MiniHomeSaveResult {
        val loaded = load()
        if (loaded !is MiniHomeLoadResult.Ready) return MiniHomeSaveResult.Forbidden
        val plantIds = loaded.plants.mapTo(mutableSetOf()) { it.id }
        val decorationIds = loaded.decorations.mapTo(mutableSetOf()) { it.id }
        val retained =
            request.layout.placements.filter { placement ->
                when (val target = placement.target) {
                    is MiniHomePlacementTarget.Plant -> target.plantId in plantIds
                    is MiniHomePlacementTarget.Decoration -> target.itemId in decorationIds
                }
            }
        return MiniHomeSaveResult.Reconciled(
            failure,
            loaded.committed,
            loaded.plants,
            loaded.decorations,
            request.layout.copy(
                placements =
                    com.planterior.helper.feature.minihome.MiniHomePlacementPolicy.layer(retained),
                revision = loaded.committed.revision,
                updatedAt = loaded.committed.updatedAt,
            ),
            request.layout.placements.size - retained.size,
        )
    }

    private suspend fun persist(account: AccountId, layout: MiniHomeLayout) {
        val dao = database.cacheDao()
        val generation =
            maxOf(
                layout.revision.value,
                (dao.currentMiniHomeCache(account.value)?.watermark?.generation ?: 0) + 1,
            )
        dao.applyAuthoritativeMiniHome(
            AuthoritativeMiniHomeCacheWrite.Layout(
                account.value,
                generation,
                "debug-cache-$generation",
                "0".repeat(64),
                CachedMiniHomeEntity(
                    account.value,
                    layout.id.value,
                    layout.name,
                    layout.placements.count { it.target is MiniHomePlacementTarget.Plant },
                    layout.revision.value,
                    layout.updatedAt.toEpochMilli(),
                ),
                layout.placements.map {
                    CachedMiniHomePlacementEntity(
                        account.value,
                        it.id.value,
                        layout.id.value,
                        (it.target as? MiniHomePlacementTarget.Plant)?.plantId?.value,
                        (it.target as? MiniHomePlacementTarget.Decoration)?.itemId?.value,
                        it.position.normalizedX.value,
                        it.position.normalizedY.value,
                        it.zIndex.value,
                        layout.revision.value,
                    )
                },
            )
        )
    }

    private fun CachedMiniHomePlacementEntity.placement(): MiniHomePlacement =
        MiniHomePlacement(
            PlacementId(placementId),
            plantId?.let { MiniHomePlacementTarget.Plant(PersonalPlantId(it)) }
                ?: MiniHomePlacementTarget.Decoration(ItemId(requireNotNull(itemId))),
            GridPosition.parsePersisted(normalizedX, normalizedY),
            MiniHomeZIndex(zIndex),
        )
}

private fun forcedAccount(context: Context): String? =
    preferences(context)
        .takeIf { it.getString(SESSION, "") == SESSION_SIGNED_IN }
        ?.getString(SESSION_ACCOUNT, "")
        ?.takeIf(String::isNotBlank)

private fun preferences(context: Context) =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

private const val LOAD_EVENT_LOG_TAG = "DebugMiniHomeLoad"
private const val STATE_EVENT_LOG_TAG = "DebugMiniHomeState"
private const val PREFERENCES = "home-qa"
private const val SESSION = "session"
private const val SESSION_ACCOUNT = "session-account"
private const val SESSION_SIGNED_IN = "session-signed-in"
private const val SAVE_OUTCOME = "mini-home-save-outcome"
private const val MINI_HOME_OUTBOX_TYPE = "miniHomeLayouts"
const val OUTCOME_SUCCESS = "success"
const val OUTCOME_FAILURE = "failure"
const val OUTCOME_CONFLICT = "conflict"
const val OUTCOME_UNAVAILABLE = "unavailable"
const val OUTCOME_MISMATCH = "mismatch"
const val OUTCOME_INVALID = "invalid"
