package com.planterior.helper

import com.planterior.helper.minihome.Todo18MiniHomeLoadBoundaryStage
import com.planterior.helper.minihome.Todo18MiniHomeLoadReceiptReducer
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
            val diagnosticEntries = snapshot.filter {
                it.source == "boundary" && it.kind in LOAD_DIAGNOSTIC_KINDS
            }
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
                        )
                    },
                )

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

    private companion object {
        val LOAD_DIAGNOSTIC_KINDS =
            setOf(
                "load-entered",
                "remote-load-entered",
                "remote-load-returned",
                "cache-apply-entered",
                "cache-apply-returned",
                "publication-read-entered",
                "publication-read-returned",
                "load-terminal",
            )
    }
}
