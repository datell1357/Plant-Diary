package com.planterior.helper

import java.io.File

/** Pre-armed, instance-owned evidence capture for the Todo18 MiniHome diagnostic. */
internal class Todo18MiniHomeInitialLoadDiagnosticCapture(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun <T> captureConflictInitialLoad(block: () -> T): T {
        val armed = arm()
        return preserveTodo18PrimaryFailure(block, armed::finish)
    }

    private fun arm(): ArmedCapture {
        val timeline = Timeline()
        val sink = runtime.renderedStateSink
        return ArmedCapture(
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
            ),
        )
    }

    private inner class ArmedCapture(
        private val timeline: Timeline,
        private val subscriptions: CaptureSubscriptions,
    ) {
        fun finish(primaryFailure: Throwable?): IllegalStateException? {
            val problems = mutableListOf<String>()
            close("load-boundary-listener", subscriptions.boundary, problems)
            close("raw-state-listener", subscriptions.raw, problems)
            close("displayed-state-listener", subscriptions.displayed, problems)

            val snapshot = timeline.snapshot()
            val progress = runtime.miniHomeLoadDiagnostics.snapshot()
            val expectedAccountId = runtime.boundary.accountId.value
            LOAD_DIAGNOSTIC_KINDS.forEach { kind ->
                if (snapshot.none { it.kind == kind }) problems += "missing-$kind"
            }
            if (
                snapshot.any { entry ->
                    entry.kind in ACCOUNT_STAGE_KINDS && entry.identity != expectedAccountId
                }
            ) {
                problems += "load-stage-account-mismatch"
            }
            val diagnosticEntries = snapshot.filter {
                it.source == "boundary" && it.kind in LOAD_DIAGNOSTIC_KINDS
            }
            if (
                diagnosticEntries.any { entry ->
                    entry.loadId == null ||
                        entry.diagnosticOrder == null ||
                        (entry.kind == PUBLICATION_READ_ENTERED && entry.readId == null)
                }
            ) {
                problems += "load-diagnostic-identity-missing"
            }
            val expectedIdentities =
                progress.observations.map { observation ->
                    DiagnosticIdentity(
                        diagnosticOrder = observation.order,
                        loadId = observation.loadId.value,
                        readId = observation.readId?.ordinal,
                        kind =
                            if (
                                observation.diagnostic
                                    is
                                    com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnostic.Terminal
                            ) {
                                LOAD_TERMINAL
                            } else {
                                observation.receiptStage
                            },
                    )
                }
            val capturedIdentities = diagnosticEntries.map { entry ->
                DiagnosticIdentity(
                    diagnosticOrder = entry.diagnosticOrder,
                    loadId = entry.loadId,
                    readId = entry.readId,
                    kind = entry.kind,
                )
            }
            if (capturedIdentities != expectedIdentities) {
                problems += "load-diagnostic-boundary-mismatch"
            }
            val terminalIdentities =
                diagnosticEntries.filter { it.kind == LOAD_TERMINAL }.mapNotNull { it.identity }
            if (terminalIdentities.any { it !in TERMINAL_IDENTITIES }) {
                problems += "unclassified-load-terminal"
            }
            problems += progress.recorderFailures.map { "recorder-failed:$it" }
            problems += progress.progressionProblems()

            val status = if (problems.isEmpty()) "complete" else problems.joinToString()
            return Todo18DiagnosticReceiptFinalizer(
                    receiptFile = ::receiptFile,
                    diagnosticName = "Todo18 MiniHome diagnostic",
                )
                .finish(primaryFailure != null, status) { receipt ->
                    writeTodo18MiniHomeInitialLoadReceipt(
                        receipt,
                        Todo18MiniHomeInitialLoadReceipt(
                            api = android.os.Build.VERSION.SDK_INT,
                            expectedAccountId = expectedAccountId,
                            timeline = snapshot,
                            progress = progress,
                            primaryFailure = primaryFailure,
                            problems = problems,
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

    private fun receiptFile(): File {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        return File(directory, "mini-home-conflict-initial-load-diagnostic.json")
    }

    private data class CaptureSubscriptions(
        val boundary: AutoCloseable,
        val raw: AutoCloseable,
        val displayed: AutoCloseable,
    )

    private data class DiagnosticIdentity(
        val diagnosticOrder: Long?,
        val loadId: Long?,
        val readId: Long?,
        val kind: String?,
    )

    private companion object {
        const val LOAD_TERMINAL = "load-terminal"
        const val PUBLICATION_READ_ENTERED = "publication-read-entered"
        val LOAD_DIAGNOSTIC_KINDS =
            setOf(
                "load-entered",
                "remote-load-entered",
                "remote-load-returned",
                PUBLICATION_READ_ENTERED,
                LOAD_TERMINAL,
            )
        val ACCOUNT_STAGE_KINDS = LOAD_DIAGNOSTIC_KINDS - LOAD_TERMINAL
        val TERMINAL_IDENTITIES = setOf("Ready", "Forbidden", "Failed", "Cancelled")
    }
}
