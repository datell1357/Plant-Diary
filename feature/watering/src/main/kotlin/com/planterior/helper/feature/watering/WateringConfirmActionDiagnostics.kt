package com.planterior.helper.feature.watering

import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import java.io.Closeable

/** Ordered, behavior-neutral confirmation boundaries. Empty unless a test capture is installed. */
enum class WateringConfirmActionStage {
    CONFIRM_NODE_COUNT,
    CONFIRM_NODE_DISPLAYED,
    CONFIRM_NODE_ENABLED,
    CONFIRM_NODE_ON_CLICK,
    SCREEN_CALLBACK,
    COROUTINE_ENTRY,
    CONTROLLER_ENTRY,
    VALIDATION_DECISION,
    SAVING_PUBLICATION,
    REPOSITORY_COMPLETE_ENTRY,
    APPLY_RESULT,
    RECEIPT_LOOKUP_ENTRY,
    RECEIPT_LOOKUP_RESULT,
    FIXTURE_RECEIPT_EMIT,
    LISTENER_DELIVERY,
}

enum class WateringConfirmActionDecision {
    ACCEPTED,
    REJECTED,
}

data class WateringConfirmActionObservation(
    val stage: WateringConfirmActionStage,
    val plantId: PersonalPlantId? = null,
    val operationId: OperationId? = null,
    val decision: WateringConfirmActionDecision? = null,
)

fun interface WateringConfirmActionSink {
    fun observe(observation: WateringConfirmActionObservation)
}

object WateringConfirmActionDiagnostics {
    private class Installation(
        val token: Any,
        val sink: WateringConfirmActionSink,
    )

    private val lock = Any()

    @Volatile private var installation: Installation? = null

    fun install(sink: WateringConfirmActionSink): Closeable {
        val installed = Installation(Any(), sink)
        synchronized(lock) {
            check(installation == null) { "Watering confirmation diagnostics already installed" }
            installation = installed
        }
        return Closeable {
            synchronized(lock) {
                if (installation?.token === installed.token) installation = null
            }
        }
    }

    fun observe(observation: WateringConfirmActionObservation) {
        val sink = installation?.sink ?: return
        try {
            sink.observe(observation)
        } catch (_: AssertionError) {
            // Diagnostics cannot alter the action.
        } catch (_: Exception) {
            // Diagnostics cannot alter the action.
        }
    }

    fun listenerCount(): Int = if (installation == null) 0 else 1
}
