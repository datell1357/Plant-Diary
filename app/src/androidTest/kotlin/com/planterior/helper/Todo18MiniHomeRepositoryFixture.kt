package com.planterior.helper

import com.planterior.helper.core.data.AuthoritativeInventory
import com.planterior.helper.core.data.INVENTORY_CONTRACT_VERSION
import com.planterior.helper.core.data.authoritativeInventorySnapshotHash
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeRemoteDataSource
import com.planterior.helper.feature.minihome.MiniHomeSaveFailure
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.RemoteMiniHomeSaveResult
import com.planterior.helper.feature.minihome.RemoteMiniHomeSnapshot
import java.security.MessageDigest

/**
 * Remote fixture for production
 * [com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository].
 */
internal class Todo18MiniHomeRepositoryFixture(private val scenario: Todo18Scenario) :
    MiniHomeRemoteDataSource {
    private var generation = 1L
    private var committedOperationId: OperationId? = null
    private var committedExpectedRevision: Revision? = null
    private var committedPayloadHash: String? = null
    private var offlineReturned = false
    private var layout =
        MiniHomeLayout(
            id = MiniHomeId("todo18-home"),
            name = "Todo18 room",
            placements = emptyList(),
            revision = Revision(1),
            updatedAt = scenario.now(),
        )

    override fun activeAccount(): AccountId = scenario.accountId

    override suspend fun load(accountId: AccountId): RemoteMiniHomeSnapshot {
        require(accountId == scenario.accountId)
        scenario.emit("mini-home-loaded", accountId.value)
        return snapshot()
    }

    override suspend fun save(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult {
        scenario.miniHomeSaveRequests += request
        scenario.emit("mini-home-save-attempt", request.operationId.value)
        return when (scenario.miniHomeSaveMode) {
            Todo18MiniHomeSaveMode.OFFLINE_ONCE -> {
                if (!offlineReturned) {
                    offlineReturned = true
                    RemoteMiniHomeSaveResult.Failed(MiniHomeSaveFailure.NETWORK)
                } else {
                    apply(request)
                }
            }
            Todo18MiniHomeSaveMode.REVISION_CONFLICT ->
                RemoteMiniHomeSaveResult.Conflict(request.expectedRevision.next())
            Todo18MiniHomeSaveMode.APPLY -> apply(request)
        }
    }

    private fun apply(request: MiniHomeSaveRequest): RemoteMiniHomeSaveResult {
        val revision = request.expectedRevision.next()
        layout = request.layout.copy(revision = revision, updatedAt = scenario.now())
        generation += 1
        committedOperationId = request.operationId
        committedExpectedRevision = request.expectedRevision
        committedPayloadHash = todo18MiniHomePayloadHash(request)
        scenario.emit("mini-home-committed", request.operationId.value)
        return RemoteMiniHomeSaveResult.Applied(revision)
    }

    private fun snapshot(): RemoteMiniHomeSnapshot =
        RemoteMiniHomeSnapshot(
            accountId = scenario.accountId,
            layout = layout,
            plants =
                scenario.plants.values.map {
                    MiniHomePlantChoice(it.id, it.displayName, it.representativePhotoPath)
                },
            decorations = emptyList(),
            committedOperationId = committedOperationId,
            committedExpectedRevision = committedExpectedRevision,
            committedPayloadHash = committedPayloadHash,
            cacheGeneration = generation,
            cacheOperationId = committedOperationId?.value,
            cachePayloadHash = committedPayloadHash,
            authoritativeAtEpochMillis = scenario.now().toEpochMilli(),
            authoritativeInventory = emptyInventory(),
            snapshotToken = generation.toString(16).padStart(64, '0'),
            snapshotGeneration = generation,
        )

    private fun emptyInventory(): AuthoritativeInventory =
        AuthoritativeInventory(
            contractVersion = INVENTORY_CONTRACT_VERSION,
            accountId = scenario.accountId,
            catalog = emptyList(),
            owned = emptyList(),
            registeredPlantCount = scenario.plants.size,
            loadedAtEpochMillis = scenario.now().toEpochMilli(),
            partial = false,
            generation = generation,
            snapshotHash =
                authoritativeInventorySnapshotHash(
                    scenario.accountId,
                    emptyList(),
                    emptyList(),
                    registeredPlantCount = scenario.plants.size,
                    partial = false,
                ),
        )
}

private fun todo18MiniHomePayloadHash(request: MiniHomeSaveRequest): String {
    val canonical = buildString {
        append("{\"expectedRevision\":${request.expectedRevision.value}")
        append(",\"miniHomeId\":\"")
        append(request.layout.id.value)
        append("\",\"name\":\"")
        append(request.layout.name.replace("\\", "\\\\").replace("\"", "\\\""))
        append("\",\"placements\":[")
        request.layout.placements.forEachIndexed { index, placement ->
            if (index > 0) append(',')
            val plant = placement.target as? MiniHomePlacementTarget.Plant
            val item = placement.target as? MiniHomePlacementTarget.Decoration
            append("{\"itemId\":")
            if (item == null) append("null") else append("\"${item.itemId.value}\"")
            append(",\"normalizedX\":${placement.position.normalizedX.value}")
            append(",\"normalizedY\":${placement.position.normalizedY.value}")
            append(",\"placementId\":\"${placement.id.value}\"")
            append(",\"plantId\":")
            if (plant == null) append("null") else append("\"${plant.plantId.value}\"")
            append(",\"zIndex\":${placement.zIndex.value}}")
        }
        append("]}")
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
