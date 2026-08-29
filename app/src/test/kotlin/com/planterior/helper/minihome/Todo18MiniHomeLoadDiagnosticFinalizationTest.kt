package com.planterior.helper.minihome

import com.planterior.helper.Todo18DiagnosticReceiptFinalizer
import com.planterior.helper.preserveTodo18PrimaryFailure
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Todo18MiniHomeLoadDiagnosticFinalizationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `invalid progression cannot finalize as complete`() {
        // Given
        val progress = invalidProgress()
        val receipt = File(temporaryFolder.root, "invalid-load.json")
        val finalizer =
            Todo18DiagnosticReceiptFinalizer(
                receiptFile = { receipt },
                diagnosticName = "Todo18 MiniHome diagnostic",
            )

        // When
        val failure =
            assertThrows(IllegalStateException::class.java) {
                preserveTodo18PrimaryFailure(
                    block = {},
                    finish = { primary ->
                        finalizer.finish(
                            primary != null,
                            progress.progressionProblems().single(),
                        ) { file ->
                            file.writeText(
                                buildJsonObject { putTodo18MiniHomeLoadProgress(progress) }
                                    .toString()
                            )
                        }
                    },
                )
            }

        // Then
        assertTrue(failure.message!!.contains("invalid-load-progression:out-of-order-stage"))
        assertTrue(receipt.readText().contains("\"valid\":false"))
    }

    @Test
    fun `invalid progression finalization preserves the exact primary failure`() {
        // Given
        val progress = invalidProgress()
        val primary = AssertionError("journey failed")
        val finalizer =
            Todo18DiagnosticReceiptFinalizer(
                receiptFile = { File(temporaryFolder.root, "primary.json") },
                diagnosticName = "Todo18 MiniHome diagnostic",
            )

        // When
        val actual =
            assertThrows(AssertionError::class.java) {
                preserveTodo18PrimaryFailure(
                    block = { throw primary },
                    finish = { failure ->
                        finalizer.finish(
                            failure != null,
                            progress.progressionProblems().single(),
                        ) {}
                    },
                )
            }

        // Then
        assertSame(primary, actual)
        assertEquals(1, actual.suppressed.size)
        assertTrue(actual.suppressed.single().message!!.contains("invalid-load-progression"))
    }

    private fun invalidProgress(): Todo18MiniHomeLoadProgress {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.recordPublicationRead()
        return recorder.snapshot()
    }
}
