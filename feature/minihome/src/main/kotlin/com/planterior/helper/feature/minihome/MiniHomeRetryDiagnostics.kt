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
    val resultDetails: MiniHomeSaveResultDetails? = result?.toRetryResultDetails(),
    val failure: Throwable? = null,
)

sealed interface MiniHomeSaveResultDetails {
    data class Saved(val layoutId: String, val revision: Long) : MiniHomeSaveResultDetails

    data class Conflict(
        val authoritativeLayoutId: String,
        val authoritativeRevision: Long,
        val plantCount: Int,
        val decorationCount: Int,
    ) : MiniHomeSaveResultDetails

    data class Failed(
        val failure: MiniHomeSaveFailure,
        val hasDiscardHandle: Boolean,
    ) : MiniHomeSaveResultDetails

    data class RequiresCorrection(
        val failure: MiniHomeSaveFailure,
        val details: String?,
        val hasDiscardHandle: Boolean,
    ) : MiniHomeSaveResultDetails

    data class RequiresReconciliation(
        val failure: MiniHomeSaveFailure,
        val hasDiscardHandle: Boolean,
    ) : MiniHomeSaveResultDetails

    data class Reconciled(
        val failure: MiniHomeSaveFailure,
        val authoritativeLayoutId: String,
        val authoritativeRevision: Long,
        val correctedDraftLayoutId: String,
        val correctedDraftRevision: Long,
        val plantCount: Int,
        val decorationCount: Int,
        val removedTargets: Int,
    ) : MiniHomeSaveResultDetails

    data class PendingChanged(
        val currentOperationPresent: Boolean,
        val operationId: String?,
        val expectedRevision: Long?,
        val layoutId: String?,
        val layoutRevision: Long?,
        val state: MiniHomePendingState?,
        val failure: MiniHomeSaveFailure?,
        val failureDetails: String?,
        val hasDiscardHandle: Boolean,
    ) : MiniHomeSaveResultDetails

    data object Forbidden : MiniHomeSaveResultDetails
}

private fun MiniHomeSaveResult.toRetryResultDetails(): MiniHomeSaveResultDetails =
    when (this) {
        is MiniHomeSaveResult.Saved ->
            MiniHomeSaveResultDetails.Saved(layout.id.value, layout.revision.value)
        is MiniHomeSaveResult.Conflict ->
            MiniHomeSaveResultDetails.Conflict(
                authoritativeLayoutId = authoritative.id.value,
                authoritativeRevision = authoritative.revision.value,
                plantCount = plants.size,
                decorationCount = decorations.size,
            )
        is MiniHomeSaveResult.Failed ->
            MiniHomeSaveResultDetails.Failed(failure, discardHandle != null)
        is MiniHomeSaveResult.RequiresCorrection ->
            MiniHomeSaveResultDetails.RequiresCorrection(failure, details, discardHandle != null)
        is MiniHomeSaveResult.RequiresReconciliation ->
            MiniHomeSaveResultDetails.RequiresReconciliation(failure, discardHandle != null)
        is MiniHomeSaveResult.Reconciled ->
            MiniHomeSaveResultDetails.Reconciled(
                failure = failure,
                authoritativeLayoutId = authoritative.id.value,
                authoritativeRevision = authoritative.revision.value,
                correctedDraftLayoutId = correctedDraft.id.value,
                correctedDraftRevision = correctedDraft.revision.value,
                plantCount = plants.size,
                decorationCount = decorations.size,
                removedTargets = removedTargets,
            )
        is MiniHomeSaveResult.PendingChanged ->
            MiniHomeSaveResultDetails.PendingChanged(
                currentOperationPresent = current != null,
                operationId = current?.operationId?.value,
                expectedRevision = current?.expectedRevision?.value,
                layoutId = current?.layout?.id?.value,
                layoutRevision = current?.layout?.revision?.value,
                state = current?.state,
                failure = current?.failure,
                failureDetails = current?.failureDetails,
                hasDiscardHandle = current?.discardHandle != null,
            )
        MiniHomeSaveResult.Forbidden -> MiniHomeSaveResultDetails.Forbidden
    }

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
