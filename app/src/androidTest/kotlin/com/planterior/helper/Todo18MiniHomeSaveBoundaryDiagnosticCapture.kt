package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.minihome.MiniHomeRetryStage
import com.planterior.helper.minihome.Todo18MiniHomeSaveBoundaryDiagnosticRecorder
import java.io.Closeable
import java.io.File

internal class Todo18MiniHomeSaveBoundaryDiagnosticCapture(
    private val compose: Todo18ComposeRule,
    private val recorder: Todo18MiniHomeSaveBoundaryDiagnosticRecorder,
    private val accountId: AccountId,
    private val operationId: OperationId,
) : Closeable {
    private var registration: Closeable? = null

    fun <T> captureRetry(trigger: () -> T): T {
        check(registration == null) { "save-boundary capture already active" }
        check(MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY.name == "REPOSITORY_SAVE_ENTRY")
        registration = recorder.install()
        return preserveTodo18PrimaryFailure(trigger, ::finish)
    }

    override fun close() {
        registration?.close()
        registration = null
    }

    private fun finish(primaryFailure: Throwable?): IllegalStateException? {
        close()
        val complete = runCatching { recorder.requireComplete(accountId, operationId) }.isSuccess
        val status = if (complete) "complete" else "partial"
        return Todo18DiagnosticReceiptFinalizer(::receiptFile, "Todo18 MiniHome save boundary")
            .finish(primaryFailure != null, status, recorder::writeTo)
    }

    private fun receiptFile(): File {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        return File(directory, "offline-save-boundary-diagnostic.json")
    }
}
