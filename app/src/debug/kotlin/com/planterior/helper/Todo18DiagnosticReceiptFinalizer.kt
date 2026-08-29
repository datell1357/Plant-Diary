package com.planterior.helper

import java.io.File

/** Debug-only guard for Todo18 diagnostic receipt creation and failure preservation. */
internal class Todo18DiagnosticReceiptFinalizer(
    private val receiptFile: () -> File,
    private val diagnosticName: String,
) {
    fun finish(
        hasPrimaryFailure: Boolean,
        status: String,
        writeReceipt: (File) -> Unit,
    ): IllegalStateException? {
        var receipt: File? = null
        var receiptFailure: Throwable? = null
        try {
            receipt = receiptFile()
            writeReceipt(receipt)
        } catch (failure: AssertionError) {
            receiptFailure = failure
        } catch (failure: Exception) {
            receiptFailure = failure
        }
        val finalStatus = if (receiptFailure == null) status else "receipt-finalization-failed"
        return IllegalStateException(
                "$diagnosticName receipt=${receipt?.absolutePath ?: "<unavailable>"}; " +
                    "status=$finalStatus",
                receiptFailure,
            )
            .takeIf { hasPrimaryFailure || finalStatus != "complete" }
    }
}

internal fun <T> preserveTodo18PrimaryFailure(
    block: () -> T,
    finish: (Throwable?) -> IllegalStateException?,
): T {
    var primaryFailure: Throwable? = null
    try {
        return block()
    } catch (failure: AssertionError) {
        primaryFailure = failure
        throw failure
    } catch (failure: Exception) {
        primaryFailure = failure
        throw failure
    } finally {
        val summary =
            try {
                finish(primaryFailure)
            } catch (failure: AssertionError) {
                IllegalStateException("Todo18 diagnostic finalization failed", failure)
            } catch (failure: Exception) {
                IllegalStateException("Todo18 diagnostic finalization failed", failure)
            }
        if (primaryFailure != null && summary != null) {
            primaryFailure.addSuppressed(summary)
        } else if (summary != null) {
            throw summary
        }
    }
}
