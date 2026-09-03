package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

enum class MiniHomeSaveBoundaryStage {
    SAVE_SCOPE_ENTERED,
    REMOTE_SAVE_ENTERED,
    REMOTE_SAVE_RETURNED,
    REMOTE_SAVE_CANCELLED,
    RECEIPT_RECORD_ENTERED,
    RECEIPT_RECORD_RETURNED,
    RECONCILE_APPLIED_ENTERED,
    AUTHORITATIVE_LOAD_ENTERED,
    AUTHORITATIVE_LOAD_RETURNED,
    AUTHORITATIVE_LOAD_CANCELLED,
    CACHE_ENTERED,
    CACHE_RETURNED,
    CACHE_CANCELLED,
    CONSUME_ENTERED,
    CONSUME_RETURNED,
    CONSUME_CANCELLED,
    RECONCILE_APPLIED_RETURNED,
    SAVE_SCOPE_RETURNED,
    SAVE_SCOPE_CANCELLED,
}

enum class MiniHomeSaveBoundaryOutcome {
    ENTERED,
    APPLIED,
    DUPLICATE,
    CONFLICT,
    FAILED,
    RECORDED,
    CURRENT,
    CONSUMED,
    SAVED,
    REQUIRES_CORRECTION,
    REQUIRES_RECONCILIATION,
    RECONCILED,
    PENDING_CHANGED,
    FORBIDDEN,
    CANCELLED,
}

data class MiniHomeSaveBoundaryObservation(
    val sequence: Long,
    val stage: MiniHomeSaveBoundaryStage,
    val accountId: AccountId,
    val operationId: OperationId,
    val outcome: MiniHomeSaveBoundaryOutcome? = null,
    val failureClass: String? = null,
    val failure: Throwable? = null,
)

fun interface MiniHomeSaveBoundarySink {
    fun observe(observation: MiniHomeSaveBoundaryObservation)
}

object MiniHomeSaveBoundaryDiagnostics {
    private data class Installation(val token: Any, val sink: MiniHomeSaveBoundarySink)

    private val lock = Any()
    private val nextSequence = AtomicLong(0L)
    private const val OBSERVER_FAULT_POLICY = "swallow"

    @Volatile private var installation: Installation? = null

    fun install(sink: MiniHomeSaveBoundarySink): Closeable {
        val installed = Installation(Any(), sink)
        synchronized(lock) {
            check(installation == null) { "MiniHome save-boundary diagnostics already installed" }
            installation = installed
        }
        return Closeable {
            synchronized(lock) {
                if (installation?.token === installed.token) installation = null
            }
        }
    }

    fun observe(observation: MiniHomeSaveBoundaryObservation) {
        val sink = installation?.sink ?: return
        try {
            sink.observe(observation)
        } catch (_: Throwable) {
            OBSERVER_FAULT_POLICY.length
        }
    }

    internal fun observe(
        stage: MiniHomeSaveBoundaryStage,
        accountId: AccountId,
        operationId: OperationId,
        outcome: MiniHomeSaveBoundaryOutcome? = null,
        failure: Throwable? = null,
    ) {
        if (installation == null) return
        observe(
            MiniHomeSaveBoundaryObservation(
                sequence = nextSequence.incrementAndGet(),
                stage = stage,
                accountId = accountId,
                operationId = operationId,
                outcome = outcome,
                failureClass = boundedFailureClass(failure),
                failure = failure,
            )
        )
    }

    fun listenerCount(): Int = if (installation == null) 0 else 1
}

internal fun boundedFailureClass(failure: Throwable?): String? {
    val name = failure?.javaClass?.name ?: return null
    return if (name.length <= 256) name else "redacted.failure-class"
}

internal fun observeSaveBoundaryCancellation(
    accountId: AccountId,
    operationId: OperationId,
    stage: MiniHomeSaveBoundaryStage,
    error: CancellationException,
): Nothing {
    val failure = error
    check(failure === error)
    MiniHomeSaveBoundaryDiagnostics.observe(
        stage = stage,
        accountId = accountId,
        operationId = operationId,
        outcome = MiniHomeSaveBoundaryOutcome.CANCELLED,
        failure = failure,
    )
    throw error
}
