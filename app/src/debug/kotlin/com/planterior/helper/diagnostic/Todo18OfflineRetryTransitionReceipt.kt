package com.planterior.helper.diagnostic

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal enum class Todo18OfflineRetryTransitionStage {
    TRIGGER_RETURNED,
    MINI_HOME_COMMITTED,
    RAW_CONTROLLER_STATE,
    ROUTE_DISPLAYED_CALLBACK,
    RENDERED_SINK_DELIVERY,
}

internal data class Todo18OfflineRetryTransitionObservation(
    val stage: Todo18OfflineRetryTransitionStage,
    val operationId: String,
    val revision: Long? = null,
)

internal data class Todo18OfflineRetryTransitionReceipt(
    val observations: List<Todo18OfflineRetryTransitionObservation>,
    val closed: Boolean,
    val outcomeClass: String? = null,
    val outcomeMessage: String? = null,
) {
    fun withPrimaryFailure(primaryFailure: Throwable): Todo18OfflineRetryTransitionReceipt =
        copy(
            outcomeClass = primaryFailure.javaClass.name,
            outcomeMessage = primaryFailure.message,
        )

    fun requireComplete(operationId: String, committedIdentity: String, finalRevision: Long) {
        require(closed) { "offline-retry-transition-unclosed" }
        require(committedIdentity == operationId) { "offline-retry-boundary-identity-mismatch" }
        require(observations.map { it.stage } == Todo18OfflineRetryTransitionStage.entries) {
            "offline-retry-transition-missing-duplicate-or-out-of-order"
        }
        require(observations.all { it.operationId == operationId }) {
            "offline-retry-transition-operation-mismatch"
        }
        require(finalRevision > 1L) { "offline-retry-final-revision-invalid" }
        require(observations.takeLast(3).all { it.revision == finalRevision }) {
            "offline-retry-transition-revision-mismatch"
        }
    }
}

internal fun writeTodo18OfflineRetryTransitionReceipt(
    file: File,
    receipt: Todo18OfflineRetryTransitionReceipt,
) {
    file.writeText(
        buildJsonObject {
            put("schema", "todo18-offline-retry-transition-v2")
            put("status", if (receipt.outcomeClass == null) "complete" else "partial")
            put("closed", receipt.closed)
            put("outcomeClass", receipt.outcomeClass?.let(::JsonPrimitive) ?: JsonNull)
            put("outcomeMessage", receipt.outcomeMessage?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("observations") {
                receipt.observations.forEach { observation ->
                    add(
                        buildJsonObject {
                            put("stage", observation.stage.name)
                            put("operationId", observation.operationId)
                            put("revision", observation.revision)
                        }
                    )
                }
            }
        }
            .toString()
    )
}

internal class Todo18OfflineRetryTransitionRecorder {
    private val lock = Any()
    private val observations = mutableListOf<Todo18OfflineRetryTransitionObservation>()
    private var closed = false

    fun record(observation: Todo18OfflineRetryTransitionObservation) {
        synchronized(lock) {
            check(!closed) { "offline-retry-transition-already-closed" }
            observations += observation
        }
    }

    fun close(primaryFailure: Throwable? = null): Todo18OfflineRetryTransitionReceipt =
        synchronized(lock) {
            check(!closed) { "offline-retry-transition-already-closed" }
            closed = true
            Todo18OfflineRetryTransitionReceipt(
                observations.toList(),
                closed = true,
                outcomeClass = primaryFailure?.javaClass?.name,
                outcomeMessage = primaryFailure?.message,
            )
        }
}
