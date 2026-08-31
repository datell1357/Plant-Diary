package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.OperationId
import java.io.Closeable

enum class MiniHomeRetryStage {
    CALLBACK_ENTRY,
    COROUTINE_ENTRY,
    COROUTINE_RETURNED,
    COROUTINE_THROWN,
    COROUTINE_CANCELLED,
    REPOSITORY_SAVE_ENTRY,
    REPOSITORY_SAVE_RETURNED,
    REPOSITORY_SAVE_THROWN,
    REPOSITORY_SAVE_CANCELLED,
    SAVED_APPLY_ENTRY,
    SAVED_APPLY_REJECTED,
    SET_STATE_ATTEMPTED,
    SET_STATE_APPLIED,
    SET_STATE_REJECTED,
    RAW_STATE_PUBLICATION,
}

data class MiniHomeRetryObservation(
    val stage: MiniHomeRetryStage,
    val operationId: OperationId? = null,
    val controllerEpoch: Long? = null,
    val controllerGeneration: Long? = null,
    val saveGeneration: Long? = null,
    val guardDraftIdentity: Boolean? = null,
    val revision: Long? = null,
    val outcome: String? = null,
    val result: MiniHomeSaveResult? = null,
    val failure: Throwable? = null,
)

fun interface MiniHomeRetrySink {
    fun observe(observation: MiniHomeRetryObservation)
}

object MiniHomeRetryDiagnostics {
    private class Installation(
        val token: Any,
        val sink: MiniHomeRetrySink,
    )

    private val lock = Any()

    @Volatile private var installation: Installation? = null

    fun install(sink: MiniHomeRetrySink): Closeable {
        val installed = Installation(Any(), sink)
        synchronized(lock) {
            check(installation == null) { "MiniHome retry diagnostics already installed" }
            installation = installed
        }
        return Closeable {
            synchronized(lock) {
                if (installation?.token === installed.token) installation = null
            }
        }
    }

    fun observe(observation: MiniHomeRetryObservation) {
        val sink = installation?.sink ?: return
        try {
            sink.observe(observation)
        } catch (_: Throwable) {}
    }

    fun listenerCount(): Int = if (installation == null) 0 else 1
}

internal suspend fun runMiniHomeRetryCoroutine(
    operationId: OperationId,
    onSave: suspend () -> Unit,
) {
    MiniHomeRetryDiagnostics.observe(
        MiniHomeRetryObservation(MiniHomeRetryStage.COROUTINE_ENTRY, operationId)
    )
    try {
        onSave()
        MiniHomeRetryDiagnostics.observe(
            MiniHomeRetryObservation(
                MiniHomeRetryStage.COROUTINE_RETURNED,
                operationId,
                outcome = "returned",
            )
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        MiniHomeRetryDiagnostics.observe(
            MiniHomeRetryObservation(
                MiniHomeRetryStage.COROUTINE_CANCELLED,
                operationId,
                outcome = "cancellation",
                failure = error,
            )
        )
        throw error
    } catch (error: Throwable) {
        MiniHomeRetryDiagnostics.observe(
            MiniHomeRetryObservation(
                MiniHomeRetryStage.COROUTINE_THROWN,
                operationId,
                outcome = "exception",
                failure = error,
            )
        )
        throw error
    }
}
