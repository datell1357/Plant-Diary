package com.planterior.helper

import com.planterior.helper.core.model.OperationId
import com.planterior.helper.diagnostic.Todo18OfflineRetryTransitionObservation
import com.planterior.helper.diagnostic.Todo18OfflineRetryTransitionReceipt
import com.planterior.helper.diagnostic.Todo18OfflineRetryTransitionRecorder
import com.planterior.helper.diagnostic.Todo18OfflineRetryTransitionStage
import com.planterior.helper.diagnostic.writeTodo18OfflineRetryTransitionReceipt
import com.planterior.helper.feature.minihome.MiniHomeRetryDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeUiState
import java.io.Closeable
import java.io.File

internal class Todo18OfflineRetryTransitionDiagnosticCapture(
    runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
    private val operationId: String,
) : AutoCloseable {
    private val recorder = Todo18OfflineRetryTransitionRecorder()
    private val registrations = mutableListOf<AutoCloseable>()
    private var receipt: Todo18OfflineRetryTransitionReceipt? = null

    init {
        registrations += MiniHomeRetryDiagnostics.install { observation ->
            if (observation.operationId?.value == operationId) {
                recorder.recordRetry(observation)
            }
        }
        registrations +=
            runtime.boundary
                .subscribe { event ->
                    if (event.kind == "mini-home-committed") {
                        recorder.record(
                            Todo18OfflineRetryTransitionObservation(
                                Todo18OfflineRetryTransitionStage.MINI_HOME_COMMITTED,
                                event.identity,
                            )
                        )
                    }
                }
                .asAutoCloseable()
        registrations +=
            runtime.renderedStateSink.subscribeToRawMiniHomeStates { event ->
                event.retryViewing()?.let { (operation, revision) ->
                    recorder.record(
                        Todo18OfflineRetryTransitionObservation(
                            Todo18OfflineRetryTransitionStage.RAW_CONTROLLER_STATE,
                            operation,
                            revision,
                        )
                    )
                }
            }
        registrations +=
            runtime.renderedStateSink.subscribeToRouteMiniHomeStates { event ->
                event.retryViewing()?.let { (operation, revision) ->
                    recorder.record(
                        Todo18OfflineRetryTransitionObservation(
                            Todo18OfflineRetryTransitionStage.ROUTE_DISPLAYED_CALLBACK,
                            operation,
                            revision,
                        )
                    )
                }
            }
        registrations +=
            runtime.renderedStateSink.subscribeToDisplayedMiniHomeStates { event ->
                event.retryViewing()?.let { (operation, revision) ->
                    recorder.record(
                        Todo18OfflineRetryTransitionObservation(
                            Todo18OfflineRetryTransitionStage.RENDERED_SINK_DELIVERY,
                            operation,
                            revision,
                        )
                    )
                }
            }
    }

    fun <T> capture(block: Todo18OfflineRetryTransitionDiagnosticCapture.() -> T): T =
        preserveTodo18PrimaryFailure({ block() }, ::finish)

    fun recordTriggerReturned() {
        recorder.record(
            Todo18OfflineRetryTransitionObservation(
                Todo18OfflineRetryTransitionStage.TRIGGER_RETURNED,
                operationId,
            )
        )
    }

    fun requireComplete(frozen: OperationId, committed: Todo18BoundaryEvent) {
        require(frozen.value == operationId)
        close()
        val finalRevision =
            requireNotNull(receipt)
                .observations
                .last { it.stage == Todo18OfflineRetryTransitionStage.RENDERED_SINK_DELIVERY }
                .revision
        requireNotNull(receipt)
            .requireComplete(
                operationId,
                committed.identity,
                requireNotNull(finalRevision),
            )
    }

    override fun close() {
        if (receipt != null) return
        registrations.asReversed().forEach(AutoCloseable::close)
        receipt = recorder.close()
    }

    private fun finish(primaryFailure: Throwable?): IllegalStateException? {
        if (receipt == null) {
            registrations.asReversed().forEach(AutoCloseable::close)
            receipt = recorder.close(primaryFailure)
        } else if (primaryFailure != null) {
            receipt = requireNotNull(receipt).withPrimaryFailure(primaryFailure)
        }
        return Todo18DiagnosticReceiptFinalizer(
                receiptFile = ::receiptFile,
                diagnosticName = "Todo18 Offline retry transition",
            )
            .finish(primaryFailure != null, "complete") { file ->
                writeTodo18OfflineRetryTransitionReceipt(file, requireNotNull(receipt))
            }
    }

    private fun receiptFile(): File {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        return File(directory, "offline-retry-transition-diagnostic.json")
    }

    private fun Todo18MiniHomeStateEvent.retryViewing(): Pair<String, Long>? {
        val viewing = state as? MiniHomeUiState.Viewing ?: return null
        val operation = viewing.exitOutcome?.operationId?.value ?: return null
        if (operation != operationId || viewing.committed.revision.value <= 1L) return null
        return operation to viewing.committed.revision.value
    }

    private fun Closeable.asAutoCloseable(): AutoCloseable = AutoCloseable(::close)
}
