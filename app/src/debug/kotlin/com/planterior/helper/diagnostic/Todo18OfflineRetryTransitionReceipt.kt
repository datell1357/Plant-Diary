package com.planterior.helper.diagnostic

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
) {
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

    fun close(): Todo18OfflineRetryTransitionReceipt =
        synchronized(lock) {
            check(!closed) { "offline-retry-transition-already-closed" }
            closed = true
            Todo18OfflineRetryTransitionReceipt(observations.toList(), closed = true)
        }
}
