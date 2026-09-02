package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassification
import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassifier
import com.planterior.helper.minihome.Todo18MiniHomeLoadBoundaryStage
import com.planterior.helper.minihome.Todo18MiniHomeLoadReceiptReducer
import com.planterior.helper.minihome.renderedViewingIdentityProblems
import java.io.File

/** Pre-armed, instance-owned evidence capture for the Todo18 MiniHome diagnostic. */
internal class Todo18MiniHomeInitialLoadDiagnosticCapture(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun <T> captureInitialLoad(scenarioName: String, block: () -> T): T {
        val armed = arm(scenarioName)
        return preserveTodo18PrimaryFailure(block, armed::finish)
    }

    private fun arm(scenarioName: String): ArmedCapture {
        val timeline = Timeline()
        val sink = runtime.renderedStateSink
        return ArmedCapture(
            scenarioName,
            timeline,
            CaptureSubscriptions(
                boundary =
                    runtime.boundary.subscribe { event ->
                        if (event.kind in LOAD_DIAGNOSTIC_KINDS) timeline.recordBoundary(event)
                    },
                raw =
                    sink.subscribeToRawMiniHomeStates { event ->
                        timeline.recordState("raw", event)
                    },
                displayed =
                    sink.subscribeToDisplayedMiniHomeStates { event ->
                        timeline.recordState("displayed", event)
                    },
                transactionOwner =
                    runtime.roomTransactionOwners.subscribe(timeline::recordTransactionOwner),
            ),
        )
    }

    private inner class ArmedCapture(
        private val scenarioName: String,
        private val timeline: Timeline,
        private val subscriptions: CaptureSubscriptions,
    ) {
        fun finish(primaryFailure: Throwable?): IllegalStateException? {
            val problems = mutableListOf<String>()
            close("load-boundary-listener", subscriptions.boundary, problems)
            close("raw-state-listener", subscriptions.raw, problems)
            close("displayed-state-listener", subscriptions.displayed, problems)
            close("transaction-owner-listener", subscriptions.transactionOwner, problems)

            val snapshot = timeline.snapshot()
            val progress = runtime.miniHomeLoadDiagnostics.snapshot()
            val expectedAccountId = runtime.boundary.accountId.value
            val diagnosticEntries = snapshot.filter {
                it.source == "boundary" && it.kind in LOAD_DIAGNOSTIC_KINDS
            }
            problems +=
                renderedViewingIdentityProblems(
                    expectedAccountId,
                    progress,
                    snapshot.map(TimelineEntry::renderedState),
                )
            problems +=
                Todo18MiniHomeLoadReceiptReducer.problems(
                    expectedAccountId,
                    progress,
                    diagnosticEntries.map { entry ->
                        Todo18MiniHomeLoadBoundaryStage(
                            kind = entry.kind,
                            identity = entry.identity,
                            loadId = entry.loadId,
                            readId = entry.readId,
                            diagnosticOrder = entry.diagnosticOrder,
                            cacheOutcome = entry.cacheOutcome,
                            pendingReadLoadId = entry.pendingReadLoadId,
                            pendingReadId = entry.pendingReadId,
                            pendingReadOutcome = entry.pendingReadOutcome,
                            operationId = entry.operationId,
                            cacheTransactionResult = entry.cacheTransactionResult,
                            cacheTransactionFailureClass = entry.cacheTransactionFailureClass,
                            cacheTransactionFailureMessage = entry.cacheTransactionFailureMessage,
                            publicationReadTerminalOutcome = entry.publicationReadTerminalOutcome,
                            publicationReadTerminalFailureClass =
                                entry.publicationReadTerminalFailureClass,
                            publicationReadTerminalFailureMessage =
                                entry.publicationReadTerminalFailureMessage,
                        )
                    },
                )

            val transactionOwner =
                Todo18RoomTransactionOwnerClassifier.classify(
                    snapshot.mapNotNull(TimelineEntry::transactionOwnerClassificationEvent)
                )
            if (
                primaryFailure != null &&
                    snapshot.any { it.kind == "publication-read-entered" && it.readId == 2L } &&
                    snapshot.none { it.kind == "publication-read-returned" && it.readId == 2L } &&
                    transactionOwner == Todo18RoomTransactionOwnerClassification.Unknown
            ) {
                problems += "shared-room-transaction-owner:UNKNOWN"
            }

            val status = if (problems.isEmpty()) "complete" else problems.joinToString()
            return Todo18DiagnosticReceiptFinalizer(
                    receiptFile = { receiptFile(scenarioName) },
                    diagnosticName = "Todo18 MiniHome diagnostic",
                )
                .finish(primaryFailure != null, status) { receipt ->
                    writeTodo18MiniHomeInitialLoadReceipt(
                        receipt,
                        Todo18MiniHomeInitialLoadReceipt(
                            scenarioName = scenarioName,
                            api = android.os.Build.VERSION.SDK_INT,
                            expectedAccountId = expectedAccountId,
                            timeline = snapshot,
                            progress = progress,
                            primaryFailure = primaryFailure,
                            problems = problems,
                            transactionOwner = transactionOwner,
                        ),
                    )
                }
        }

        private fun close(
            label: String,
            closeable: AutoCloseable,
            problems: MutableList<String>,
        ) {
            try {
                closeable.close()
            } catch (failure: Exception) {
                problems += "$label-close-failed:${failure.javaClass.name}"
            }
        }
    }

    private fun receiptFile(scenarioName: String): File {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        return File(directory, "$scenarioName-diagnostic.json")
    }

    private data class CaptureSubscriptions(
        val boundary: AutoCloseable,
        val raw: AutoCloseable,
        val displayed: AutoCloseable,
        val transactionOwner: AutoCloseable,
    )

    private companion object {
        val LOAD_DIAGNOSTIC_KINDS =
            setOf(
                "load-entered",
                "remote-load-entered",
                "remote-load-returned",
                "cache-apply-entered",
                "cache-apply-returned",
                "cache-transaction-call-entered",
                "cache-transaction-body-entered",
                "cache-layout-apply",
                "cache-inventory-apply",
                "cache-current-snapshot",
                "cache-verified-inventory-decode",
                "cache-transaction-body-returned",
                "cache-transaction-scope-returned",
                "cache-terminal-conflict",
                "cache-transaction-returned",
                "cache-transaction-threw",
                "cache-transaction-cancelled",
                "publication-read-entered",
                "publication-read-returned",
                "publication-read-terminal-returned",
                "publication-read-terminal-threw",
                "publication-read-terminal-cancelled",
                "pending-read-entered",
                "pending-read-returned",
                "pending-read-threw",
                "pending-read-cancelled",
                "load-terminal",
            )
    }
}
