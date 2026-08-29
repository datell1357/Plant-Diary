package com.planterior.helper.diagnostic

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.PlantContentId

enum class Todo18StateChannel {
    MINI_HOME_RAW,
    MINI_HOME_DISPLAYED,
    REGISTRATION,
}

enum class Todo18StateKind {
    MINI_HOME_LOADING,
    MINI_HOME_UNAVAILABLE,
    MINI_HOME_VIEWING,
    MINI_HOME_EDITING,
    MINI_HOME_FORBIDDEN,
    MINI_HOME_ERROR,
    REGISTRATION_LOADING_SESSION,
    REGISTRATION_SESSION_FAILED,
    REGISTRATION_EDITING,
    REGISTRATION_CHECKING_DUPLICATES,
    REGISTRATION_DUPLICATE_FOUND,
    REGISTRATION_SAVING,
    REGISTRATION_SAVE_FAILED,
    REGISTRATION_COMPLETED,
}

enum class Todo18StateDispatchPhase {
    BEGIN,
    RETURN,
    FAILURE,
}

enum class Todo18DiagnosticRecordKind {
    PIPELINE,
    STATE_DISPATCH,
    EXACT_EVENT,
}

enum class Todo18ExactEventPhase {
    SUBSCRIBED,
    ARMED,
    TRIGGER_BEGIN,
    TRIGGER_RETURN,
    TRIGGER_FAILURE,
    EVENT_RECEIVED,
    PREDICATE_TRUE,
    PREDICATE_FALSE,
    EVENT_ACCEPTED,
    EVENT_REJECTED,
    AWAIT_SUCCESS,
    AWAIT_FAILURE,
    DETACH,
    DRAIN,
    FINAL_LISTENER_COUNT,
}

data class Todo18ExactEventObservation(
    val ordinal: Long = 0L,
    val phase: Todo18ExactEventPhase,
    val matchingCount: Int? = null,
    val listenerCount: Int? = null,
    val sourceSequence: Long? = null,
)

interface Todo18ExactEventObserver {
    fun observe(observation: Todo18ExactEventObservation)

    fun diagnosticFailure() = Unit
}

data class Todo18StateSnapshot(
    val sequence: Long,
    val channel: Todo18StateChannel,
    val state: Todo18StateKind,
    val owner: AccountId?,
    val selectedContentId: PlantContentId?,
)

data class Todo18StateDispatchRecord(
    val ordinal: Long,
    val waitId: Todo18WaitId,
    val sourceSequence: Long,
    val channel: Todo18StateChannel,
    val state: Todo18StateKind,
    val owner: AccountId?,
    val selectedContentId: PlantContentId?,
    val currentBefore: Todo18StateSnapshot?,
    val currentAfter: Todo18StateSnapshot?,
    val primaryListenerCount: Int,
    val phase: Todo18StateDispatchPhase,
    val freshForWait: Boolean,
    val isolatedInstance: Boolean,
)

data class Todo18CaptureFreshness(
    val initialSequence: Long,
    val initialCurrentsEmpty: Boolean,
    val initialListenerCount: Int,
    val isolatedInstance: Boolean,
) {
    val fresh: Boolean
        get() = initialSequence == 0L && initialCurrentsEmpty && initialListenerCount == 0
}

data class Todo18DiagnosticCaptureSnapshot(
    val waitId: Todo18WaitId,
    val freshness: Todo18CaptureFreshness,
    val pipeline: List<Todo18PipelineEvent>,
    val stateDispatches: List<Todo18StateDispatchRecord>,
    val exactEvents: List<Todo18ExactEventObservation>,
    val failures: List<Todo18DiagnosticFailure>,
    val closed: Boolean,
)

fun interface Todo18RecorderFaultInjector {
    fun beforeRecord(kind: Todo18DiagnosticRecordKind)
}
