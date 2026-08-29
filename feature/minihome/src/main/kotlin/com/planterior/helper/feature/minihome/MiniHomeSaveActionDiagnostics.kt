package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.OperationId
import java.io.Closeable

/** Ordered, behavior-neutral save boundaries. Empty unless a test capture is installed. */
enum class MiniHomeSaveActionStage {
    SAVE_NODE_COUNT,
    SAVE_NODE_DISPLAYED,
    SAVE_NODE_ENABLED,
    SAVE_NODE_ON_CLICK,
    SCREEN_CALLBACK,
    COROUTINE_ENTRY,
    CONTROLLER_ENTRY,
    GUARD_DECISION,
    VALIDATION_DECISION,
    SAVING_PUBLICATION,
    FIXTURE_SAVE_ENTRY,
    FIXTURE_EVENT_EMIT,
    LISTENER_DELIVERY,
}

enum class MiniHomeSaveActionDecision {
    ACCEPTED,
    REJECTED,
}

data class MiniHomeSaveActionObservation(
    val stage: MiniHomeSaveActionStage,
    val operationId: OperationId? = null,
    val decision: MiniHomeSaveActionDecision? = null,
)

fun interface MiniHomeSaveActionSink {
    fun observe(observation: MiniHomeSaveActionObservation)
}

object MiniHomeSaveActionDiagnostics {
    private class Installation(
        val token: Any,
        val sink: MiniHomeSaveActionSink,
    )

    private val lock = Any()

    @Volatile private var installation: Installation? = null

    fun install(sink: MiniHomeSaveActionSink): Closeable {
        val installed = Installation(Any(), sink)
        synchronized(lock) {
            check(installation == null) { "MiniHome save diagnostics already installed" }
            installation = installed
        }
        return Closeable {
            synchronized(lock) {
                if (installation?.token === installed.token) installation = null
            }
        }
    }

    fun observe(observation: MiniHomeSaveActionObservation) {
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
