package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18DiagnosticProvenance
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import java.io.File

internal enum class Todo18IntegratedActionKind {
    MINI_HOME_SAVE,
    WATERING_CONFIRM,
}

internal data class Todo18IntegratedSemanticFacts(
    val nodeCount: Int? = null,
    val displayed: Boolean? = null,
    val enabled: Boolean? = null,
    val onClick: Boolean? = null,
)

internal data class Todo18IntegratedActionObservation(
    val ordinal: Int,
    val stage: String,
    val operationId: String?,
    val plantId: String?,
)

internal data class Todo18IntegratedActionSnapshot(
    val scenario: String,
    val kind: Todo18IntegratedActionKind,
    val observations: List<Todo18IntegratedActionObservation>,
    val semanticFacts: Todo18IntegratedSemanticFacts,
    val bindingValidated: Boolean,
    val boundaryDelivered: Boolean,
    val initialListenerCount: Int,
    val finalListenerCount: Int,
    val captureClosed: Boolean,
)

internal object Todo18IntegratedActionReducer {
    fun firstFailure(snapshot: Todo18IntegratedActionSnapshot): String? {
        if (snapshot.scenario.isBlank()) return "malformed scenario"
        if (!snapshot.captureClosed) return "unclosed capture"
        if (!snapshot.bindingValidated) return "provenance binding mismatch"
        if (!snapshot.boundaryDelivered) return "missing boundary delivery"
        if (snapshot.initialListenerCount != 2 || snapshot.finalListenerCount != 2) {
            return "action sink listener count mismatch"
        }
        if (snapshot.semanticFacts.nodeCount != 1) return "semantic nodeCount mismatch"
        if (snapshot.semanticFacts.displayed != true) return "semantic displayed mismatch"
        if (snapshot.semanticFacts.enabled != true) return "semantic enabled mismatch"
        if (snapshot.semanticFacts.onClick != true) return "semantic onClick mismatch"
        val expected =
            when (snapshot.kind) {
                Todo18IntegratedActionKind.MINI_HOME_SAVE ->
                    MiniHomeSaveActionStage.entries.map(Enum<*>::name)
                Todo18IntegratedActionKind.WATERING_CONFIRM ->
                    WateringConfirmActionStage.entries.map(Enum<*>::name)
            }
        snapshot.observations.forEachIndexed { index, observation ->
            if (observation.ordinal != index + 1) return "malformed ordinal at ${index + 1}"
            val required =
                expected.getOrNull(index) ?: return "duplicate or extra ${observation.stage}"
            if (observation.stage != required) {
                return if (observation.stage in expected.drop(index + 1)) "missing $required"
                else "out-of-order or duplicate ${observation.stage}"
            }
        }
        if (snapshot.observations.size < expected.size) {
            return "missing ${expected[snapshot.observations.size]}"
        }
        val operationIds = snapshot.observations.map { it.operationId }.toSet()
        if (null in operationIds || operationIds.size != 1) return "operation identity mismatch"
        if (snapshot.kind == Todo18IntegratedActionKind.WATERING_CONFIRM) {
            val plantIds = snapshot.observations.map { it.plantId }.toSet()
            if (null in plantIds || plantIds.size != 1) return "plant identity mismatch"
        }
        return null
    }
}

internal class Todo18IntegratedActionReceiptFinalizer(
    private val receiptFile: () -> File,
    private val diagnosticName: String,
    private val provenance: Todo18DiagnosticProvenance,
) {
    fun finish(
        snapshot: Todo18IntegratedActionSnapshot,
        primary: Throwable?,
    ): IllegalStateException? {
        val reductionFailure = Todo18IntegratedActionReducer.firstFailure(snapshot)
        val status = if (reductionFailure == null) "complete" else "invalid-action-capture"
        return Todo18DiagnosticReceiptFinalizer(receiptFile, diagnosticName).finish(
            primary != null,
            status,
        ) { file ->
            file.writeText(snapshot.toJson(provenance, primary, reductionFailure, status))
        }
    }
}

private fun Todo18IntegratedActionSnapshot.toJson(
    provenance: Todo18DiagnosticProvenance,
    primary: Throwable?,
    reductionFailure: String?,
    status: String,
): String =
    jsonObject(
        "schema" to "todo18-integrated-action-diagnostic-v1",
        "scenario" to scenario,
        "kind" to kind.name,
        "expectedSourceSha256" to provenance.expectedSourceSha256,
        "embeddedSourceSha256" to provenance.embeddedSourceSha256,
        "expectedAppApkSha256" to provenance.expectedAppApkSha256,
        "observedAppApkSha256" to provenance.observedAppApkSha256,
        "expectedAndroidTestApkSha256" to provenance.expectedAndroidTestApkSha256,
        "observedAndroidTestApkSha256" to provenance.observedAndroidTestApkSha256,
        "bindingValidated" to bindingValidated,
        "semanticFacts" to RawJson(semanticFacts.toJson()),
        "observations" to observations.map { RawJson(it.toJson()) },
        "boundaryDelivered" to boundaryDelivered,
        "initialActionSinkListenerCount" to initialListenerCount,
        "finalActionSinkListenerCount" to finalListenerCount,
        "captureClosed" to captureClosed,
        "reductionFailure" to reductionFailure,
        "originalFailureClass" to primary?.javaClass?.name,
        "originalFailureMessage" to primary?.message,
        "finalizationState" to status,
    )

private fun Todo18IntegratedSemanticFacts.toJson() =
    jsonObject(
        "nodeCount" to nodeCount,
        "displayed" to displayed,
        "enabled" to enabled,
        "onClick" to onClick,
    )

private fun Todo18IntegratedActionObservation.toJson() =
    jsonObject(
        "ordinal" to ordinal,
        "stage" to stage,
        "operationId" to operationId,
        "plantId" to plantId,
    )

private data class RawJson(val value: String)

private fun jsonObject(vararg fields: Pair<String, Any?>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (name, value) ->
        "${name.toJsonString()}:${value.toJsonValue()}"
    }

private fun Any?.toJsonValue(): String =
    when (this) {
        null -> "null"
        is String -> toJsonString()
        is Boolean,
        is Number -> toString()
        is RawJson -> value
        is Iterable<*> -> joinToString(prefix = "[", postfix = "]") { it.toJsonValue() }
        else -> error("Unsupported diagnostic JSON value: ${javaClass.name}")
    }

private fun String.toJsonString(): String = buildString {
    append('"')
    this@toJsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}
