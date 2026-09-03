package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundaryDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundaryObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundarySink
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundaryStage
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class Todo18MiniHomeSaveBoundaryDiagnosticRecorder(
    private val writer: (String) -> Unit = {}
) : MiniHomeSaveBoundarySink {
    private val lock = Any()
    private val observations = mutableListOf<MiniHomeSaveBoundaryObservation>()

    override fun observe(observation: MiniHomeSaveBoundaryObservation) {
        val json = serialize(observation)
        synchronized(lock) { observations += observation }
        try {
            writer(json)
        } catch (_: Throwable) {}
    }

    fun install() = MiniHomeSaveBoundaryDiagnostics.install(this)

    fun snapshot(): List<MiniHomeSaveBoundaryObservation> =
        synchronized(lock) { observations.toList() }

    fun serializedSnapshot(): List<String> = snapshot().map(::serialize)

    fun requireComplete(accountId: AccountId, operationId: OperationId) {
        val captured = snapshot()
        require(captured.map { it.stage } == SUCCESS_STAGES) {
            "mini-home-save-boundary-stage-order"
        }
        require(captured.all { it.accountId == accountId }) {
            "mini-home-save-boundary-account-mismatch"
        }
        require(captured.all { it.operationId == operationId }) {
            "mini-home-save-boundary-operation-mismatch"
        }
        require(
            captured.zipWithNext().all { (first, second) -> first.sequence < second.sequence }
        ) {
            "mini-home-save-boundary-sequence-order"
        }
    }

    fun writeTo(file: File) {
        file.writeText(buildJsonArray { snapshot().forEach { add(it.toJson()) } }.toString())
    }

    private fun serialize(observation: MiniHomeSaveBoundaryObservation): String =
        observation.toJson().toString()

    private fun MiniHomeSaveBoundaryObservation.toJson(): JsonObject = buildJsonObject {
        put("sequence", sequence)
        put("stage", stage.name)
        put("accountId", accountId.value)
        put("operationId", operationId.value)
        put("outcome", outcome?.name)
        put("failureClass", safeFailureClass(failureClass))
    }

    companion object {
        const val RETRY_RECEIPT_CARDINALITY_NOTE = "exactly 9 retry observations remain unchanged"

        val SUCCESS_STAGES =
            listOf(
                MiniHomeSaveBoundaryStage.SAVE_SCOPE_ENTERED,
                MiniHomeSaveBoundaryStage.REMOTE_SAVE_ENTERED,
                MiniHomeSaveBoundaryStage.REMOTE_SAVE_RETURNED,
                MiniHomeSaveBoundaryStage.RECEIPT_RECORD_ENTERED,
                MiniHomeSaveBoundaryStage.RECEIPT_RECORD_RETURNED,
                MiniHomeSaveBoundaryStage.RECONCILE_APPLIED_ENTERED,
                MiniHomeSaveBoundaryStage.AUTHORITATIVE_LOAD_ENTERED,
                MiniHomeSaveBoundaryStage.AUTHORITATIVE_LOAD_RETURNED,
                MiniHomeSaveBoundaryStage.CACHE_ENTERED,
                MiniHomeSaveBoundaryStage.CACHE_RETURNED,
                MiniHomeSaveBoundaryStage.CONSUME_ENTERED,
                MiniHomeSaveBoundaryStage.CONSUME_RETURNED,
                MiniHomeSaveBoundaryStage.RECONCILE_APPLIED_RETURNED,
                MiniHomeSaveBoundaryStage.SAVE_SCOPE_RETURNED,
            )
    }
}

private fun safeFailureClass(value: String?): String? =
    value?.takeIf { it.length <= 256 } ?: value?.let { "redacted.failure-class" }
