package com.planterior.helper

internal class Todo18IntegratedActionDiagnosticCapture(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val scenario: String,
    kind: Todo18IntegratedActionKind,
) {
    private val provenance = captureTodo18DiagnosticProvenance()
    private val capture =
        runtime.actionRecorder.start(scenario, kind, runtime.actionListenerCount())
    private val receiptFinalizer =
        Todo18IntegratedActionReceiptFinalizer(
            receiptFile = { todo18IntegratedActionReceiptFile(scenario) },
            diagnosticName = "Todo18 $scenario action",
            provenance = provenance,
        )

    fun <T> run(block: (Todo18IntegratedActionRecorder.Capture) -> T): T =
        preserveTodo18PrimaryFailure(
            block = { block(capture) },
            finish = ::finalizeReceipt,
        )

    private fun finalizeReceipt(primary: Throwable?): IllegalStateException? {
        val snapshot = capture.close(runtime.actionListenerCount(), provenance.bindingValidated)
        return receiptFinalizer.finish(snapshot, primary)
    }
}
