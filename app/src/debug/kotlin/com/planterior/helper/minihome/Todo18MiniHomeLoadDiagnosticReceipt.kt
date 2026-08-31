package com.planterior.helper.minihome

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun JsonObjectBuilder.putTodo18MiniHomeLoadProgress(progress: Todo18MiniHomeLoadProgress) {
    put("valid", progress.valid)
    put("activeStage", progress.activeStage)
    put("lastReachedStage", progress.lastReachedStage)
    putJsonArray("reachedStages") { progress.reachedStages.forEach(::add) }
    putJsonArray("observations") {
        progress.observations.forEach { observation ->
            add(
                buildJsonObject {
                    put("order", observation.order)
                    put("loadId", observation.loadId.value)
                    put("readId", observation.readId?.ordinal)
                    put("stage", observation.receiptStage)
                    put("pendingReadLoadId", observation.pendingReadId?.loadId?.value)
                    put("pendingReadId", observation.pendingReadId?.queryOrdinal)
                    put(
                        "pendingReadOutcome",
                        when (observation.diagnostic) {
                            is Todo18MiniHomeLoadDiagnostic.PendingReadReturned -> "returned"
                            is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> "threw"
                            is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled -> "cancelled"
                            else -> null
                        },
                    )
                    val pendingFailure =
                        when (val diagnostic = observation.diagnostic) {
                            is Todo18MiniHomeLoadDiagnostic.PendingReadThrew -> diagnostic.failure
                            is Todo18MiniHomeLoadDiagnostic.PendingReadCancelled ->
                                diagnostic.failure
                            else -> null
                        }
                    put("pendingReadFailureClass", pendingFailure?.javaClass?.name)
                    put("pendingReadFailureMessage", pendingFailure?.message)
                    val cacheAccountId =
                        when (val diagnostic = observation.diagnostic) {
                            is Todo18MiniHomeLoadDiagnostic.CacheApplyEntered ->
                                diagnostic.accountId.value
                            is Todo18MiniHomeLoadDiagnostic.CacheApplyReturned ->
                                diagnostic.accountId.value
                            else -> null
                        }
                    put("cacheAccountId", cacheAccountId)
                    put(
                        "cacheOutcome",
                        (observation.diagnostic as? Todo18MiniHomeLoadDiagnostic.CacheApplyReturned)
                            ?.let { if (it.current) "current" else "conflict" },
                    )
                }
            )
        }
    }
    putJsonArray("loads") {
        progress.loads.forEach { load ->
            add(
                buildJsonObject {
                    put("loadId", load.loadId.value)
                    put("activeStage", load.activeStage)
                    put("lastReachedStage", load.lastReachedStage)
                    putJsonArray("reachedStages") { load.reachedStages.forEach(::add) }
                    putJsonArray("publicationReadIds") {
                        load.publicationReadIds.forEach { add(it.ordinal) }
                    }
                    putJsonArray("pendingReadIds") {
                        load.pendingReadIds.forEach { add(it.queryOrdinal) }
                    }
                }
            )
        }
    }
    putJsonArray("progressionViolations") {
        progress.progressionViolations.forEach { violation ->
            add(
                buildJsonObject {
                    put("kind", violation.kind.receiptValue)
                    put("loadId", violation.loadId.value)
                    put("readId", violation.readId?.ordinal)
                    put("observedStage", violation.observedStage)
                    put("previousStage", violation.previousStage)
                }
            )
        }
    }
}
